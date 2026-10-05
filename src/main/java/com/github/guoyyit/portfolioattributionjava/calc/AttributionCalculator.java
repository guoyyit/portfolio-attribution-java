package com.github.guoyyit.portfolioattributionjava.calc;

import com.github.guoyyit.portfolioattributionjava.error.ErrorCode;
import com.github.guoyyit.portfolioattributionjava.error.RejectionException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Brinson-Hood-Beebower（BHB）归因。
 *
 * <p>在每个估值子区间 i 上做标准分解：
 * <ul>
 *   <li>配置 A_s,i = (wP_s,i - wB_s,i)(rB_s,i - rB_i)</li>
 *   <li>选择 S_s,i = wB_s,i (rP_s,i - rB_s,i)</li>
 *   <li>交互 I_s,i = (wP_s,i - wB_s,i)(rP_s,i - rB_s,i)</li>
 * </ul>
 * 每个子区间内 Σ_s(A+S+I) = rP_i - rB_i 严格成立。发生在没有估值快照的日期的
 * 现金流按与 TWR 相同的规则归入包含它的子区间，不会静默忽略。
 *
 * <p>跨期链接：先求区间 TWR 收益 R_P、R_B，按公共系数
 * k = (R_P - R_B) / Σ_i (rP_i - rB_i) 缩放三项，再将残余（正常情况下仅为
 * 浮点误差）计入交互项，保证三项合计与超额收益严格一致。单期时 k=1，
 * 退化为教科书 BHB。
 *
 * <p>缺少行业拆分时的退化行为：只要组合或基准任一侧没有行业数据，
 * 就退化为总量层面分解——配置效应与交互效应为 0，选择效应等于全部超额收益，
 * 行业明细为空。恒等式 配置+选择+交互 = 超额收益 在该模式下同样严格成立。
 * 两侧都有行业数据但行业集合不一致时，以 SECTOR_SET_MISMATCH 拒绝。
 */
@Component
public class AttributionCalculator {

    private static final double TINY = 1e-12;

    private final ReturnCalculator returnCalculator;

    public AttributionCalculator(ReturnCalculator returnCalculator) {
        this.returnCalculator = returnCalculator;
    }

    public AttributionResult attribute(SectorSeries portfolio, SectorSeries benchmark) {
        LocalDate[] dates = portfolio.dates();
        if (dates.length < 2) {
            throw new RejectionException(ErrorCode.INVALID_REQUEST,
                    "attribution requires at least two valuation dates");
        }
        if (benchmark.dates().length != dates.length) {
            throw new RejectionException(ErrorCode.INVALID_REQUEST,
                    "portfolio and benchmark must share the same valuation date grid");
        }
        for (int i = 0; i < dates.length; i++) {
            if (!dates[i].equals(benchmark.dates()[i])) {
                throw new RejectionException(ErrorCode.INVALID_REQUEST,
                        "portfolio and benchmark must share the same valuation date grid");
            }
        }

        double portfolioReturn = returnCalculator.timeWeightedReturn(portfolio);
        double benchmarkReturn = returnCalculator.timeWeightedReturn(benchmark);
        double excessReturn = portfolioReturn - benchmarkReturn;

        if (portfolio.sectorValues().isEmpty() || benchmark.sectorValues().isEmpty()) {
            // 任一侧缺少行业拆分：退化为总量层面分解，恒等式仍然严格成立。
            return new AttributionResult(
                    portfolioReturn,
                    benchmarkReturn,
                    excessReturn,
                    0d,
                    excessReturn,
                    0d,
                    List.of());
        }

        int subPeriods = dates.length - 1;
        double[] pSubReturns = totalSubReturns(portfolio);
        double[] bSubReturns = totalSubReturns(benchmark);

        Map<String, double[][]> pSector = sectorSubReturns(portfolio);
        Map<String, double[][]> bSector = sectorSubReturns(benchmark);
        if (!pSector.keySet().equals(bSector.keySet())) {
            throw new RejectionException(ErrorCode.SECTOR_SET_MISMATCH,
                    "portfolio and benchmark sector sets must match");
        }

        double arithExcess = 0d;
        for (int i = 0; i < subPeriods; i++) {
            arithExcess += pSubReturns[i] - bSubReturns[i];
        }
        double linkFactor = Math.abs(arithExcess) < TINY
                ? 1d
                : excessReturn / arithExcess;

        List<SectorAttribution> sectors = new ArrayList<>();
        double allocation = 0d;
        double selection = 0d;
        double interaction = 0d;
        for (String sectorId : new TreeMap<>(Map.copyOf(pSector)).keySet()) {
            double[] pReturns = pSector.get(sectorId)[0];
            double[] pWeights = pSector.get(sectorId)[1];
            double[] bReturns = bSector.get(sectorId)[0];
            double[] bWeights = bSector.get(sectorId)[1];

            double rawAllocation = 0d;
            double rawSelection = 0d;
            double rawInteraction = 0d;
            for (int i = 0; i < subPeriods; i++) {
                double activeWeight = pWeights[i] - bWeights[i];
                double activeReturn = pReturns[i] - bReturns[i];
                rawAllocation += activeWeight * (bReturns[i] - bSubReturns[i]);
                rawSelection += bWeights[i] * activeReturn;
                rawInteraction += activeWeight * activeReturn;
            }
            double sectorAllocation = linkFactor * rawAllocation;
            double sectorSelection = linkFactor * rawSelection;
            double sectorInteraction = linkFactor * rawInteraction;
            allocation += sectorAllocation;
            selection += sectorSelection;
            interaction += sectorInteraction;

            sectors.add(new SectorAttribution(
                    sectorId,
                    pWeights[0],
                    bWeights[0],
                    sectorTwr(pReturns),
                    sectorTwr(bReturns),
                    sectorAllocation,
                    sectorSelection,
                    sectorInteraction));
        }

        double residual = excessReturn - (allocation + selection + interaction);
        interaction = distributeResidual(sectors, residual, interaction);

        return new AttributionResult(
                portfolioReturn,
                benchmarkReturn,
                excessReturn,
                allocation,
                selection,
                interaction,
                sectors);
    }

    private double distributeResidual(List<SectorAttribution> sectors,
                                      double residual, double interaction) {
        if (sectors.isEmpty() || Math.abs(residual) < TINY) {
            return interaction;
        }
        double weightBase = 0d;
        for (SectorAttribution sector : sectors) {
            weightBase += Math.abs(sector.allocationEffect())
                    + Math.abs(sector.selectionEffect())
                    + Math.abs(sector.interactionEffect());
        }
        List<SectorAttribution> updated = new ArrayList<>(sectors.size());
        double assigned = 0d;
        for (int i = 0; i < sectors.size(); i++) {
            SectorAttribution sector = sectors.get(i);
            double share;
            if (weightBase < TINY) {
                share = residual / sectors.size();
            } else {
                double magnitude = Math.abs(sector.allocationEffect())
                        + Math.abs(sector.selectionEffect())
                        + Math.abs(sector.interactionEffect());
                share = i == sectors.size() - 1
                        ? residual - assigned
                        : residual * magnitude / weightBase;
                assigned += share;
            }
            updated.add(i, new SectorAttribution(
                    sector.sectorId(),
                    sector.portfolioWeight(),
                    sector.benchmarkWeight(),
                    sector.portfolioSectorReturn(),
                    sector.benchmarkSectorReturn(),
                    sector.allocationEffect(),
                    sector.selectionEffect(),
                    sector.interactionEffect() + share));
        }
        sectors.clear();
        sectors.addAll(updated);
        return interaction + residual;
    }

    private double sectorTwr(double[] subReturns) {
        double compounded = 1d;
        for (double subReturn : subReturns) {
            compounded *= 1d + subReturn;
        }
        return compounded - 1d;
    }

    private double[] totalSubReturns(SectorSeries series) {
        LocalDate[] dates = series.dates();
        double[] totals = series.totals();
        double[] intervalFlows = ReturnCalculator.subIntervalFlows(dates, series.flows());
        double[] subReturns = new double[dates.length - 1];
        for (int i = 1; i < dates.length; i++) {
            double endBeforeFlow = totals[i] - intervalFlows[i - 1];
            if (totals[i - 1] == 0d) {
                if (endBeforeFlow != 0d) {
                    throw new RejectionException(ErrorCode.INVALID_REQUEST,
                            "zero base total value at " + dates[i - 1]);
                }
                subReturns[i - 1] = 0d;
            } else {
                subReturns[i - 1] = (endBeforeFlow - totals[i - 1]) / totals[i - 1];
            }
        }
        return subReturns;
    }

    /**
     * 返回 map: sectorId -> [子区间收益率数组, 子区间期初权重数组]。
     */
    private Map<String, double[][]> sectorSubReturns(SectorSeries series) {
        LocalDate[] dates = series.dates();
        double[] totals = series.totals();
        Map<String, double[]> sectorFlows = sectorIntervalFlows(series);

        Map<String, double[][]> result = new HashMap<>();
        for (Map.Entry<String, double[]> entry : series.sectorValues().entrySet()) {
            String sectorId = entry.getKey();
            double[] values = entry.getValue();
            double[] flows = sectorFlows.getOrDefault(sectorId, new double[dates.length - 1]);
            double[] returns = new double[dates.length - 1];
            double[] weights = new double[dates.length - 1];
            for (int i = 1; i < dates.length; i++) {
                double baseTotal = totals[i - 1];
                if (baseTotal <= 0d) {
                    throw new RejectionException(ErrorCode.INVALID_REQUEST,
                            "positive start total value is required for attribution at "
                                    + dates[i - 1]);
                }
                weights[i - 1] = values[i - 1] / baseTotal;
                double endBeforeFlow = values[i] - flows[i - 1];
                if (values[i - 1] == 0d) {
                    if (endBeforeFlow != 0d) {
                        throw new RejectionException(ErrorCode.INVALID_REQUEST,
                                "sector '" + sectorId + "' has market gain from zero base at "
                                        + dates[i - 1]);
                    }
                    returns[i - 1] = 0d;
                } else {
                    returns[i - 1] = (endBeforeFlow - values[i - 1]) / values[i - 1];
                }
            }
            result.put(sectorId, new double[][]{returns, weights});
        }
        return result;
    }

    /**
     * 返回 map: sectorId -> 每个估值子区间的现金流净额。
     * 归并规则与 TWR 一致：现金流归入包含其日期的子区间 (d_(i-1), d_i]。
     */
    private Map<String, double[]> sectorIntervalFlows(SectorSeries series) {
        Map<String, double[]> flows = new HashMap<>();
        for (CashFlowPoint flow : series.flows()) {
            if (flow.sectorId() == null) {
                throw new RejectionException(ErrorCode.SECTOR_CASH_FLOW_MISSING,
                        "every cash flow must be assigned to a sector for attribution");
            }
            int interval = ReturnCalculator.intervalIndex(series.dates(), flow.date());
            if (interval < 0) {
                continue;
            }
            flows.computeIfAbsent(flow.sectorId(),
                    ignored -> new double[series.dates().length - 1])[interval]
                    += flow.amount();
        }
        return flows;
    }
}

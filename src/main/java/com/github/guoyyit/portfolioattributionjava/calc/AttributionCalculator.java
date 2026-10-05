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
 * <p>在每个外部现金流切出的子区间 i 上做标准分解：
 * <ul>
 *   <li>配置 A_s,i = (wP_s,i - wB_s,i)(rB_s,i - rB_i)</li>
 *   <li>选择 S_s,i = wB_s,i (rP_s,i - rB_s,i)</li>
 *   <li>交互 I_s,i = (wP_s,i - wB_s,i)(rP_s,i - rB_s,i)</li>
 * </ul>
 * 每个子区间内 Σ_s(A+S+I) = rP_i - rB_i 严格成立。
 *
 * <p>跨期链接：先求区间 TWR 收益 R_P、R_B，按公共系数
 * k = (R_P - R_B) / Σ_i (rP_i - rB_i) 缩放三项，再将残余（正常情况下仅为
 * 浮点误差）计入交互项，保证三项合计与超额收益严格一致。单期时 k=1，
 * 退化为教科书 BHB。
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

        boolean portfolioHasSectors = !portfolio.sectorValues().isEmpty();
        boolean benchmarkHasSectors = !benchmark.sectorValues().isEmpty();
        if (portfolioHasSectors != benchmarkHasSectors) {
            String missingSide = portfolioHasSectors ? "benchmark" : "portfolio";
            String providedSide = portfolioHasSectors ? "portfolio" : "benchmark";
            throw new RejectionException(ErrorCode.SECTOR_BREAKDOWN_MISSING,
                    "the " + missingSide
                            + " side has no sector breakdown while the " + providedSide
                            + " side does; BHB attribution is undefined when sector "
                            + "information is available on one side only");
        }

        // 两侧都没有行业拆分时，整体作为单一聚合组做 BHB：配置/交互恒为 0，
        // 选择效应等于总超额收益，恒等关系依旧成立。
        if (!portfolioHasSectors) {
            return aggregateAttribution(
                    portfolioReturn, benchmarkReturn, excessReturn);
        }

        validateSectorCoverage(portfolio, "portfolio");
        validateSectorCoverage(benchmark, "benchmark");

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

        // 最终对齐：不同求和路径存在 ULP 级差异，用组合级交互项吸收该差异，
        // 使三项合计在输出舍入之前与超额收益逐位相等（对齐量至多为 ULP 量级，
        // 远小于 2e-6 的输出容差；无行业拆分时走 aggregateAttribution）。
        interaction = excessReturn - allocation - selection;

        return new AttributionResult(
                portfolioReturn,
                benchmarkReturn,
                excessReturn,
                allocation,
                selection,
                interaction,
                sectors);
    }

    /**
     * 两侧都没有行业拆分的场景：整体视为单一聚合组。组内配置权重差为 0，
     * 故配置效应与交互效应均为 0，选择效应 = rP - rB。三项合计严格等于
     * 超额收益，{@code sectors} 输出列表保持为空（响应结构不变）。
     */
    private AttributionResult aggregateAttribution(
            double portfolioReturn, double benchmarkReturn, double excessReturn) {
        return new AttributionResult(
                portfolioReturn,
                benchmarkReturn,
                excessReturn,
                0d,
                excessReturn,
                0d,
                List.of());
    }

    /**
     * 同一侧的行业集合必须覆盖整个日期轴：对任意已出现过的行业，每个估值日期
     * 都必须有该行业的拆分记录。部分日期缺行业行属于“单侧/部分缺失行业信息”，
     * 无法定义分解，以稳定的错误码拒绝。
     */
    private void validateSectorCoverage(SectorSeries series, String side) {
        LocalDate[] dates = series.dates();
        for (Map.Entry<String, boolean[]> entry : series.sectorPresence().entrySet()) {
            boolean[] present = entry.getValue();
            for (int i = 0; i < dates.length; i++) {
                if (!present[i]) {
                    throw new RejectionException(ErrorCode.SECTOR_BREAKDOWN_MISSING,
                            "sector '" + entry.getKey() + "' on the " + side
                                    + " side has no breakdown at " + dates[i]
                                    + "; every sector must be reported at every valuation "
                                    + "date for attribution to be defined");
                }
            }
        }
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
            if (i == sectors.size() - 1) {
                // 最后一个行业兜底：保证各项份额之和与残差逐位相等，
                // 使行业级效应之和在输出舍入之前严格等于超额收益。
                share = residual - assigned;
            } else if (weightBase < TINY) {
                share = residual / sectors.size();
                assigned += share;
            } else {
                double magnitude = Math.abs(sector.allocationEffect())
                        + Math.abs(sector.selectionEffect())
                        + Math.abs(sector.interactionEffect());
                share = residual * magnitude / weightBase;
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
        // 组合级交互效应直接取行业级交互项之和，与行业级求和路径逐位一致；
        // 由此 allocation + selection + interaction 与超额收益严格相等。
        double interactionTotal = 0d;
        for (SectorAttribution sector : sectors) {
            interactionTotal += sector.interactionEffect();
        }
        return interactionTotal;
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
        Map<LocalDate, Double> flows = netFlowsByDate(series, null);
        double[] subReturns = new double[dates.length - 1];
        for (int i = 1; i < dates.length; i++) {
            double flow = flows.getOrDefault(dates[i], 0d);
            double endBeforeFlow = totals[i] - flow;
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
        Map<LocalDate, Map<String, Double>> sectorFlows = sectorFlowsByDate(series);

        Map<String, double[][]> result = new HashMap<>();
        for (Map.Entry<String, double[]> entry : series.sectorValues().entrySet()) {
            String sectorId = entry.getKey();
            double[] values = entry.getValue();
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
                double flow = sectorFlows
                        .getOrDefault(dates[i], Map.of())
                        .getOrDefault(sectorId, 0d);
                double endBeforeFlow = values[i] - flow;
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

    private Map<LocalDate, Double> netFlowsByDate(SectorSeries series, String sectorId) {
        Map<LocalDate, Double> flows = new HashMap<>();
        for (CashFlowPoint flow : series.flows()) {
            if (sectorId != null && !sectorId.equals(flow.sectorId())) {
                continue;
            }
            flows.merge(flow.date(), flow.amount(), Double::sum);
        }
        return flows;
    }

    private Map<LocalDate, Map<String, Double>> sectorFlowsByDate(SectorSeries series) {
        Map<LocalDate, Map<String, Double>> flows = new HashMap<>();
        for (CashFlowPoint flow : series.flows()) {
            if (flow.sectorId() == null) {
                throw new RejectionException(ErrorCode.SECTOR_CASH_FLOW_MISSING,
                        "every cash flow must be assigned to a sector for attribution");
            }
            flows.computeIfAbsent(flow.date(), ignored -> new HashMap<>())
                    .merge(flow.sectorId(), flow.amount(), Double::sum);
        }
        return flows;
    }
}

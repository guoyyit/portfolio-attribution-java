package com.github.guoyyit.portfolioattributionjava.calc;

import com.github.guoyyit.portfolioattributionjava.error.ErrorCode;
import com.github.guoyyit.portfolioattributionjava.error.RejectionException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 收益计算。
 *
 * <p>约定：估值快照总市值 V_i 为日期 d_i 日终、且当日外部现金流已入账之后的市值；
 * F_i 为 d_i 当日入账的外部现金流净额（申购/注资为正，赎回/提取为负）。
 *
 * <p>时间加权（TWR）：子区间收益率 r_i = (V_i - V_(i-1) - F_i) / V_(i-1)，
 * 区间收益 R = ∏(1 + r_i) - 1。任意区间可由相邻子区间精确复合。
 *
 * <p>资金加权（MWR，Modified Dietz）：
 * MWR = (V_n - V_0 - ΣF) / (V_0 + Σ w_i F_i)，
 * w_i = (d_n - d_i) / (d_n - d_0)，起始日现金流已包含在 V_0 中，不重复计权。
 */
@Component
public class ReturnCalculator {

    private static final double NET_FLOW_TOLERANCE = 1e-12;

    public double timeWeightedReturn(SectorSeries series) {
        LocalDate[] dates = series.dates();
        double[] totals = series.totals();
        if (dates.length < 2) {
            return 0d;
        }
        requireValuationOnFlowDates(series);
        Map<LocalDate, Double> flowByDate = netFlowsByDate(series);
        double compounded = 1d;
        for (int i = 1; i < dates.length; i++) {
            double base = totals[i - 1];
            double flow = flowByDate.getOrDefault(dates[i], 0d);
            double endBeforeFlow = totals[i] - flow;
            if (base == 0d) {
                if (endBeforeFlow != 0d) {
                    throw new RejectionException(ErrorCode.INVALID_REQUEST,
                            "zero base valuation at " + dates[i - 1]
                                    + "; a valuation on each cash-flow date is required");
                }
                continue;
            }
            double subReturn = (endBeforeFlow - base) / base;
            compounded *= 1d + subReturn;
        }
        return compounded - 1d;
    }

    public double moneyWeightedReturn(SectorSeries series) {
        LocalDate[] dates = series.dates();
        double[] totals = series.totals();
        if (dates.length < 2) {
            return 0d;
        }
        requireValuationOnFlowDates(series);
        LocalDate start = dates[0];
        LocalDate end = dates[dates.length - 1];
        long totalDays = ChronoUnit.DAYS.between(start, end);
        double beginValue = totals[0];
        double endValue = totals[totals.length - 1];

        double netFlow = 0d;
        double weightedFlow = 0d;
        for (CashFlowPoint flow : series.flows()) {
            if (!flow.date().isAfter(start) || flow.date().isAfter(end)) {
                continue;
            }
            double weight = totalDays == 0 ? 0d
                    : (double) ChronoUnit.DAYS.between(flow.date(), end) / totalDays;
            netFlow += flow.amount();
            weightedFlow += weight * flow.amount();
        }
        double denominator = beginValue + weightedFlow;
        if (denominator == 0d) {
            return 0d;
        }
        return (endValue - beginValue - netFlow) / denominator;
    }

    private Map<LocalDate, Double> netFlowsByDate(SectorSeries series) {
        Map<LocalDate, Double> flowByDate = new HashMap<>();
        for (CashFlowPoint flow : series.flows()) {
            flowByDate.merge(flow.date(), flow.amount(), Double::sum);
        }
        return flowByDate;
    }

    /**
     * 区间内部现金流（严格位于 (d0, dn] 的日期）必须落在估值日期轴上。
     *
     * <p>TWR 需要在现金流当日切分子区间并取得“扣除现金流后的期末市值”；
     * 若当日没有估值快照，TWR 无法对该现金流定义子区间收益，而 MWR 仍会把它
     * 计入分子与加权分母——两种口径将给出互相矛盾的处理。遇到这种数据组合时，
     * 以 {@link ErrorCode#CASH_FLOW_WITHOUT_VALUATION} 稳定拒绝，拒绝原因只
     * 取决于输入数据，与请求顺序无关。
     *
     * <p>同一日期多笔现金流按净额归并；净额为 0（容差 1e-12）的日期对两种口径
     * 都没有影响，允许缺少快照。
     */
    public void requireValuationOnFlowDates(SectorSeries series) {
        LocalDate[] dates = series.dates();
        if (dates.length == 0) {
            return;
        }
        LocalDate start = dates[0];
        LocalDate end = dates[dates.length - 1];
        Set<LocalDate> grid = new HashSet<>();
        for (LocalDate date : dates) {
            grid.add(date);
        }
        Map<LocalDate, Double> netByDate = netFlowsByDate(series);
        for (Map.Entry<LocalDate, Double> entry : netByDate.entrySet()) {
            LocalDate date = entry.getKey();
            boolean insideWindow = date.isAfter(start) && !date.isAfter(end);
            if (insideWindow && !grid.contains(date)
                    && Math.abs(entry.getValue()) > NET_FLOW_TOLERANCE) {
                throw new RejectionException(ErrorCode.CASH_FLOW_WITHOUT_VALUATION,
                        "external cash flow dated " + date
                                + " has no valuation snapshot on that date; TWR cannot be "
                                + "defined while MWR would include the flow, so the request "
                                + "is rejected. Submit a valuation for " + date
                                + " or correct/remove the cash flow");
            }
        }
    }

    /**
     * 对显式提交计算的现金流做窗口校验：日期必须落在 (start, end] 内；
     * 起始日现金流视为已包含在起始日终市值 V_0 中。
     */
    public void validateFlowWindow(java.util.List<CashFlowPoint> flows,
                                   LocalDate start, LocalDate end) {
        for (CashFlowPoint flow : flows) {
            if (flow.date() == null) {
                throw new RejectionException(ErrorCode.INVALID_REQUEST,
                        "cash flow date is required");
            }
            if (!flow.date().isAfter(start) || flow.date().isAfter(end)) {
                throw new RejectionException(ErrorCode.CASH_FLOW_OUT_OF_RANGE,
                        "cash flow dated " + flow.date()
                                + " falls outside the calculation window ("
                                + start + ", " + end + "]");
            }
        }
    }
}

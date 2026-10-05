package com.github.guoyyit.portfolioattributionjava.calc;

import com.github.guoyyit.portfolioattributionjava.error.ErrorCode;
import com.github.guoyyit.portfolioattributionjava.error.RejectionException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 收益计算。
 *
 * <p>约定：估值快照总市值 V_i 为日期 d_i 日终、且当日外部现金流已入账之后的市值；
 * F_i 为子区间 (d_(i-1), d_i] 内入账的外部现金流净额（申购/注资为正，赎回/提取为负）。
 *
 * <p>现金流时点边界：发生在没有估值快照的日期 t 的现金流，归入包含 t 的子区间
 * (d_(i-1), d_i]（即视为不晚于下一个估值日 d_i 入账）。两种口径都计入窗口
 * (d0, dn] 内的每一笔现金流，不允许任何一口径静默忽略：
 * <ul>
 *   <li>TWR 按子区间归并：r_i = (V_i - V_(i-1) - F_i) / V_(i-1)，
 *       子区间划分只取决于估值日期轴，因此任意区间仍可由相邻子区间精确复合；</li>
 *   <li>MWR 按现金流的实际日期计权，不需要现金流日存在估值快照。</li>
 * </ul>
 *
 * <p>时间加权（TWR）：区间收益 R = ∏(1 + r_i) - 1。
 *
 * <p>资金加权（MWR，Modified Dietz）：
 * MWR = (V_n - V_0 - ΣF) / (V_0 + Σ w_i F_i)，
 * w_i = (d_n - d_i) / (d_n - d_0)，起始日现金流已包含在 V_0 中，不重复计权。
 */
@Component
public class ReturnCalculator {

    public double timeWeightedReturn(SectorSeries series) {
        LocalDate[] dates = series.dates();
        double[] totals = series.totals();
        if (dates.length < 2) {
            return 0d;
        }
        double[] intervalFlows = subIntervalFlows(dates, series.flows());
        double compounded = 1d;
        for (int i = 1; i < dates.length; i++) {
            double base = totals[i - 1];
            double endBeforeFlow = totals[i] - intervalFlows[i - 1];
            if (base == 0d) {
                if (endBeforeFlow != 0d) {
                    throw new RejectionException(ErrorCode.INVALID_REQUEST,
                            "zero base valuation at " + dates[i - 1]
                                    + " with non-zero market change in ("
                                    + dates[i - 1] + ", " + dates[i] + "]");
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

    /**
     * 把窗口 (dates[0], dates[last]] 内的现金流按子区间归并：
     * 返回数组下标 i 对应子区间 (dates[i], dates[i+1]] 的现金流净额。
     * 现金流日期不需要存在估值快照；落在窗口外的现金流不参与计算。
     * 归并结果只取决于估值日期轴与现金流本身，与录入顺序无关。
     */
    static double[] subIntervalFlows(LocalDate[] dates, List<CashFlowPoint> flows) {
        double[] intervalFlows = new double[dates.length - 1];
        for (CashFlowPoint flow : flows) {
            int interval = intervalIndex(dates, flow.date());
            if (interval >= 0) {
                intervalFlows[interval] += flow.amount();
            }
        }
        return intervalFlows;
    }

    /**
     * 返回日期 t 所属子区间的下标：dates[i] &lt; t &lt;= dates[i+1] 时为 i；
     * t 不在 (dates[0], dates[last]] 内时返回 -1。
     */
    static int intervalIndex(LocalDate[] dates, LocalDate t) {
        if (t == null || dates.length < 2
                || !t.isAfter(dates[0]) || t.isAfter(dates[dates.length - 1])) {
            return -1;
        }
        int lo = 0;
        int hi = dates.length - 1;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (t.isAfter(dates[mid])) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo;
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

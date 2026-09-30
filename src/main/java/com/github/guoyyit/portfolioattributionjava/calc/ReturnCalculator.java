package com.github.guoyyit.portfolioattributionjava.calc;

import com.github.guoyyit.portfolioattributionjava.error.ErrorCode;
import com.github.guoyyit.portfolioattributionjava.error.RejectionException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

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

    public double timeWeightedReturn(SectorSeries series) {
        LocalDate[] dates = series.dates();
        double[] totals = series.totals();
        if (dates.length < 2) {
            return 0d;
        }
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

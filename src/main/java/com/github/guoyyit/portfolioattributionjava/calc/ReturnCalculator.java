package com.github.guoyyit.portfolioattributionjava.calc;

import com.github.guoyyit.portfolioattributionjava.domain.CashFlow;
import com.github.guoyyit.portfolioattributionjava.domain.ValuationSnapshot;
import com.github.guoyyit.portfolioattributionjava.web.ApiException;
import com.github.guoyyit.portfolioattributionjava.web.ErrorCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 时间加权与资金加权收益计算。
 *
 * <p>TWR：对相邻估值日形成的子区间，收益率 r_t = (V_t - CF_t) / V_{t-1} - 1
 * （收盘估值口径，V_t 已含当日现金流），区间收益为 ∏(1+r_t) - 1。
 * 复合在全精度增长因子上进行，仅在最终结果与对外暴露的子区间收益上舍入，
 * 因此“直接一次计算”与“由子区间结果复合”始终一致（差异仅为最终舍入误差）。</p>
 */
@Component
public class ReturnCalculator {

    static final int SUB_SCALE = 12;
    static final int RESULT_SCALE = 10;

    private static final int INTERNAL_SCALE = 20;
    private static final BigDecimal ONE = BigDecimal.ONE;

    /**
     * 时间加权收益（按相邻估值日切分子区间并复合）。
     * valuations 已按键排序，cashFlows 为区间内现金流。
     */
    public BigDecimal timeWeightedReturn(Map<LocalDate, ValuationSnapshot> valuations,
                                         List<CashFlow> cashFlows) {
        return compoundedFactor(valuations, cashFlows).subtract(ONE)
                .setScale(RESULT_SCALE, RoundingMode.HALF_UP);
    }

    /** 各子区间（相邻估值日之间）的时间加权收益率（对外舍入到 SUB_SCALE）。 */
    public List<BigDecimal> subPeriodReturns(Map<LocalDate, ValuationSnapshot> valuations,
                                             List<CashFlow> cashFlows) {
        List<BigDecimal> factors = growthFactors(valuations, cashFlows);
        List<BigDecimal> subReturns = new ArrayList<>(factors.size());
        for (BigDecimal factor : factors) {
            subReturns.add(factor.subtract(ONE).setScale(SUB_SCALE, RoundingMode.HALF_UP));
        }
        return subReturns;
    }

    /** 资金加权收益（区间内部收益率，年化，ACT/365）。 */
    public BigDecimal moneyWeightedReturn(Map<LocalDate, ValuationSnapshot> valuations,
                                          List<CashFlow> cashFlows) {
        SortedMap<LocalDate, ValuationSnapshot> sorted = sortedCopy(valuations);
        LocalDate start = sorted.firstKey();
        LocalDate end = sorted.lastKey();
        BigDecimal startValue = sorted.get(start).totalValue();
        BigDecimal endValue = sorted.get(end).totalValue();
        if (startValue.signum() <= 0) {
            throw new ApiException(ErrorCode.MONEY_WEIGHTED_NO_SOLUTION,
                    "期初资产为零或为负，资金加权收益无解");
        }

        // 投资者视角现金流序列（t 年, 金额）：期初投入 -V_start，
        // 区间内投入为负、取出为正，期末收回 V_end（已剔除末日现金流）。
        // 收盘口径：期末价值 V_end 已包含 endDate 当日现金流，因此末日现金流
        // 不再单独列示，只通过期末价值出现一次，避免重复计入。
        List<double[]> flows = new ArrayList<>();
        flows.add(new double[]{0.0, -startValue.doubleValue()});
        for (CashFlow cashFlow : cashFlows) {
            if (cashFlow.date().isEqual(end)) {
                continue;
            }
            double years = ChronoUnit.DAYS.between(start, cashFlow.date()) / 365.0;
            flows.add(new double[]{years, -cashFlow.amount().doubleValue()});
        }
        flows.add(new double[]{ChronoUnit.DAYS.between(start, end) / 365.0,
                endValue.doubleValue()});

        double irr = solveIrr(flows);
        return BigDecimal.valueOf(irr).setScale(RESULT_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal compoundedFactor(Map<LocalDate, ValuationSnapshot> valuations,
                                        List<CashFlow> cashFlows) {
        BigDecimal factor = ONE;
        for (BigDecimal growthFactor : growthFactors(valuations, cashFlows)) {
            factor = factor.multiply(growthFactor);
        }
        return factor;
    }

    private List<BigDecimal> growthFactors(Map<LocalDate, ValuationSnapshot> valuations,
                                           List<CashFlow> cashFlows) {
        SortedMap<LocalDate, ValuationSnapshot> sorted = sortedCopy(valuations);
        List<BigDecimal> factors = new ArrayList<>();
        LocalDate previousDate = null;
        BigDecimal previousValue = null;
        for (Map.Entry<LocalDate, ValuationSnapshot> entry : sorted.entrySet()) {
            LocalDate currentDate = entry.getKey();
            BigDecimal currentValue = entry.getValue().totalValue();
            if (previousDate != null) {
                final LocalDate periodStart = previousDate;
                final LocalDate periodEnd = currentDate;
                BigDecimal flowSum = cashFlows.stream()
                        .filter(cf -> cf.date().isAfter(periodStart)
                                && !cf.date().isAfter(periodEnd))
                        .map(CashFlow::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal adjusted = currentValue.subtract(flowSum);
                factors.add(growthFactor(previousValue, adjusted));
            }
            previousDate = currentDate;
            previousValue = currentValue;
        }
        return factors;
    }

    private BigDecimal growthFactor(BigDecimal startValue, BigDecimal endValueExFlows) {
        if (startValue.signum() == 0) {
            if (endValueExFlows.signum() == 0) {
                return ONE;
            }
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "子区间期初资产为零且期末存在资产变动，无法计算时间加权收益");
        }
        return endValueExFlows.divide(startValue, INTERNAL_SCALE, RoundingMode.HALF_UP);
    }

    private double solveIrr(List<double[]> flows) {
        java.util.function.DoubleUnaryOperator npv = rate -> {
            double total = 0.0;
            for (double[] flow : flows) {
                total += flow[1] / Math.pow(1.0 + rate, flow[0]);
            }
            return total;
        };
        double atZero = npv.applyAsDouble(0.0);
        if (Math.abs(atZero) < 1e-12) {
            return 0.0;
        }
        double low = -0.999999d;
        double high = 1.0d;
        double npvLow = npv.applyAsDouble(low);
        double npvHigh = npv.applyAsDouble(high);
        int expansions = 0;
        while (npvLow * npvHigh > 0 && expansions < 60) {
            if (atZero > 0) {
                high *= 2;
                npvHigh = npv.applyAsDouble(high);
            } else {
                low = low / 2 - 0.5d;
                if (low <= -1.0d) {
                    low = -0.99999999d;
                }
                npvLow = npv.applyAsDouble(low);
            }
            expansions++;
        }
        if (npvLow * npvHigh > 0) {
            throw new ApiException(ErrorCode.MONEY_WEIGHTED_NO_SOLUTION,
                    "现金流序列不存在有意义的内部收益率");
        }
        for (int i = 0; i < 200; i++) {
            double mid = (low + high) / 2;
            double npvMid = npv.applyAsDouble(mid);
            if (Math.abs(npvMid) < 1e-13 || high - low < 1e-13) {
                return mid;
            }
            if (npvLow * npvMid <= 0) {
                high = mid;
                npvHigh = npvMid;
            } else {
                low = mid;
                npvLow = npvMid;
            }
        }
        return (low + high) / 2;
    }

    private SortedMap<LocalDate, ValuationSnapshot> sortedCopy(
            Map<LocalDate, ValuationSnapshot> valuations) {
        if (valuations instanceof SortedMap<LocalDate, ValuationSnapshot> sorted
                && sorted.comparator() == null) {
            return sorted;
        }
        return new TreeMap<>(valuations);
    }
}

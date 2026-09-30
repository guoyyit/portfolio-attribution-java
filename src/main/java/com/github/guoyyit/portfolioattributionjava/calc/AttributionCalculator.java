package com.github.guoyyit.portfolioattributionjava.calc;

import com.github.guoyyit.portfolioattributionjava.domain.AttributionResult;
import com.github.guoyyit.portfolioattributionjava.domain.CashFlow;
import com.github.guoyyit.portfolioattributionjava.domain.SegmentAttribution;
import com.github.guoyyit.portfolioattributionjava.domain.ValuationSnapshot;
import com.github.guoyyit.portfolioattributionjava.web.ApiException;
import com.github.guoyyit.portfolioattributionjava.web.ErrorCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 相对基准的超额收益 BHB（Brinson-Hood-Beebower）归因分解。
 *
 * <p>对每个相邻估值日子区间 t，以期初权重计算单日效应：</p>
 * <ul>
 *   <li>配置效应：(wp_i - wb_i) * rb_i</li>
 *   <li>选择效应：wb_i * (rp_i - rb_i)</li>
 *   <li>交互效应：(wp_i - wb_i) * (rp_i - rb_i)</li>
 * </ul>
 * <p>单日三项之和恒等于 rp_t - rb_t。跨子区间采用精确链接权重
 * C_t = (∏_{s&lt;t}(1+rb_s)) · (∏_{s&gt;t}(1+rp_s))（即“前序基准增长 ×
 * 后续组合增长”），由 (a·b − c·d) = a(b−d) + d(a−c) 的逐级展开保证：
 * Σ_t (rp_t − rb_t) · C_t = ∏(1+rp_t) − ∏(1+rb_t)。
 * 因此区间三项之和与区间超额收益仅差最终舍入误差（详见 docs/attribution.md）。</p>
 */
@Component
public class AttributionCalculator {

    private static final int SCALE = 20;
    private static final int RESULT_SCALE = ReturnCalculator.RESULT_SCALE;
    private static final BigDecimal ONE = BigDecimal.ONE;

    private final ReturnCalculator returnCalculator;

    public AttributionCalculator(ReturnCalculator returnCalculator) {
        this.returnCalculator = returnCalculator;
    }

    public AttributionResult attribute(String portfolioId,
                                       LocalDate startDate,
                                       LocalDate endDate,
                                       Map<LocalDate, ValuationSnapshot> portfolioValuations,
                                       Map<LocalDate, ValuationSnapshot> benchmarkValuations,
                                       List<CashFlow> cashFlows) {
        TreeMap<LocalDate, ValuationSnapshot> p = new TreeMap<>(portfolioValuations);
        TreeMap<LocalDate, ValuationSnapshot> b = new TreeMap<>(benchmarkValuations);

        BigDecimal portfolioReturn = returnCalculator.timeWeightedReturn(p, cashFlows);
        BigDecimal benchmarkReturn = returnCalculator.timeWeightedReturn(b, List.of());

        Set<String> segmentNames = new LinkedHashSet<>();
        for (ValuationSnapshot snapshot : p.subMap(startDate, true, endDate, true).values()) {
            segmentNames.addAll(snapshot.segments().keySet());
        }
        for (ValuationSnapshot snapshot : b.subMap(startDate, true, endDate, true).values()) {
            segmentNames.addAll(snapshot.segments().keySet());
        }

        List<LocalDate> dates = new ArrayList<>(
                p.navigableKeySet().subSet(startDate, true, endDate, true));
        List<BigDecimal> portfolioFactors = new ArrayList<>();
        List<BigDecimal> benchmarkFactors = new ArrayList<>();
        LocalDate previous = null;
        for (LocalDate date : dates) {
            if (previous != null) {
                final LocalDate periodStart = previous;
                final LocalDate periodEnd = date;
                BigDecimal periodFlow = cashFlows.stream()
                        .filter(cf -> cf.date().isAfter(periodStart)
                                && !cf.date().isAfter(periodEnd))
                        .map(CashFlow::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                portfolioFactors.add(ratioFactor(
                        p.get(periodStart).totalValue(),
                        p.get(periodEnd).totalValue().subtract(periodFlow)));
                benchmarkFactors.add(ratioFactor(
                        b.get(periodStart).totalValue(),
                        b.get(periodEnd).totalValue()));
            }
            previous = date;
        }

        int periods = portfolioFactors.size();
        List<BigDecimal> preBenchmark = prefixProducts(benchmarkFactors);
        List<BigDecimal> postPortfolio = suffixProducts(portfolioFactors);

        Map<String, BigDecimal[]> segmentSums = new TreeMap<>();
        for (String segment : segmentNames) {
            segmentSums.put(segment, new BigDecimal[]{
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
        }
        BigDecimal totalAllocation = BigDecimal.ZERO;
        BigDecimal totalSelection = BigDecimal.ZERO;
        BigDecimal totalInteraction = BigDecimal.ZERO;

        for (int t = 0; t < periods; t++) {
            LocalDate periodStart = dates.get(t);
            LocalDate periodEnd = dates.get(t + 1);
            ValuationSnapshot pStart = p.get(periodStart);
            ValuationSnapshot pEnd = p.get(periodEnd);
            ValuationSnapshot bStart = b.get(periodStart);
            ValuationSnapshot bEnd = b.get(periodEnd);

            BigDecimal linkWeight = preBenchmark.get(t).multiply(postPortfolio.get(t + 1));

            BigDecimal dayAllocation = BigDecimal.ZERO;
            BigDecimal daySelection = BigDecimal.ZERO;
            BigDecimal dayInteraction = BigDecimal.ZERO;
            for (String segment : segmentNames) {
                BigDecimal segmentFlow = cashFlows.stream()
                        .filter(cf -> cf.date().isAfter(periodStart)
                                && !cf.date().isAfter(periodEnd)
                                && segment.equals(cf.segment()))
                        .map(CashFlow::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                // 期初权重取期初市值占比；板块持有期收益为
                // (期末值 - 当期流入该板块现金流) / 期初值 - 1，
                // 保证 Σ wp_i·rp_i 恒等于组合层面含现金流的日收益率。
                BigDecimal wp = weight(pStart, segment);
                BigDecimal wb = weight(bStart, segment);
                BigDecimal rp = segmentReturn(pStart, pEnd, segment, segmentFlow);
                BigDecimal rb = segmentReturn(bStart, bEnd, segment, BigDecimal.ZERO);

                BigDecimal allocation = wp.subtract(wb).multiply(rb);
                BigDecimal selection = wb.multiply(rp.subtract(rb));
                BigDecimal interaction = wp.subtract(wb).multiply(rp.subtract(rb));

                dayAllocation = dayAllocation.add(allocation);
                daySelection = daySelection.add(selection);
                dayInteraction = dayInteraction.add(interaction);

                BigDecimal[] sums = segmentSums.get(segment);
                sums[0] = sums[0].add(allocation.multiply(linkWeight));
                sums[1] = sums[1].add(selection.multiply(linkWeight));
                sums[2] = sums[2].add(interaction.multiply(linkWeight));
            }

            totalAllocation = totalAllocation.add(dayAllocation.multiply(linkWeight));
            totalSelection = totalSelection.add(daySelection.multiply(linkWeight));
            totalInteraction = totalInteraction.add(dayInteraction.multiply(linkWeight));
        }

        List<SegmentAttribution> segmentAttributions = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> entry : segmentSums.entrySet()) {
            BigDecimal[] sums = entry.getValue();
            BigDecimal allocation = rounded(sums[0]);
            BigDecimal selection = rounded(sums[1]);
            BigDecimal interaction = rounded(sums[2]);
            segmentAttributions.add(new SegmentAttribution(
                    entry.getKey(), allocation, selection, interaction,
                    allocation.add(selection).add(interaction)));
        }

        BigDecimal allocation = rounded(totalAllocation);
        BigDecimal selection = rounded(totalSelection);
        BigDecimal interaction = rounded(totalInteraction);
        BigDecimal excess = portfolioReturn.subtract(benchmarkReturn)
                .setScale(RESULT_SCALE, RoundingMode.HALF_UP);

        return new AttributionResult(
                portfolioId, startDate, endDate,
                portfolioReturn, benchmarkReturn, excess,
                allocation, selection, interaction,
                segmentAttributions);
    }

    /** pre[i] = ∏_{s<i} factor_s，pre[0] = 1。 */
    private List<BigDecimal> prefixProducts(List<BigDecimal> factors) {
        List<BigDecimal> prefix = new ArrayList<>(factors.size() + 1);
        BigDecimal acc = ONE;
        prefix.add(acc);
        for (BigDecimal factor : factors) {
            acc = acc.multiply(factor);
            prefix.add(acc);
        }
        return prefix;
    }

    /** post[i] = ∏_{s>=i} factor_s，post[n] = 1。 */
    private List<BigDecimal> suffixProducts(List<BigDecimal> factors) {
        List<BigDecimal> suffix = new ArrayList<>(factors.size() + 1);
        for (int i = 0; i <= factors.size(); i++) {
            suffix.add(null);
        }
        BigDecimal acc = ONE;
        suffix.set(factors.size(), acc);
        for (int i = factors.size() - 1; i >= 0; i--) {
            acc = acc.multiply(factors.get(i));
            suffix.set(i, acc);
        }
        return suffix;
    }

    private BigDecimal weight(ValuationSnapshot snapshot, String segment) {
        if (snapshot.totalValue().signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal value = snapshot.segments().getOrDefault(segment, BigDecimal.ZERO);
        return value.divide(snapshot.totalValue(), SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal segmentReturn(ValuationSnapshot start, ValuationSnapshot end,
                                     String segment, BigDecimal flow) {
        BigDecimal startValue = start.segments().getOrDefault(segment, BigDecimal.ZERO);
        BigDecimal endValue = end.segments().getOrDefault(segment, BigDecimal.ZERO);
        return ratioFactor(startValue, endValue.subtract(flow)).subtract(ONE);
    }

    private BigDecimal ratioFactor(BigDecimal startValue, BigDecimal endValueExFlows) {
        if (startValue.signum() == 0) {
            if (endValueExFlows.signum() == 0) {
                return ONE;
            }
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "子区间期初资产为零且期末存在资产变动，无法计算收益");
        }
        return endValueExFlows.divide(startValue, SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal rounded(BigDecimal value) {
        return value.setScale(RESULT_SCALE, RoundingMode.HALF_UP);
    }
}

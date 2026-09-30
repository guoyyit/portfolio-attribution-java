package com.github.guoyyit.portfolioattributionjava.tests;

import com.github.guoyyit.portfolioattributionjava.calc.AttributionCalculator;
import com.github.guoyyit.portfolioattributionjava.calc.ReturnCalculator;
import com.github.guoyyit.portfolioattributionjava.domain.AttributionResult;
import com.github.guoyyit.portfolioattributionjava.domain.CashFlow;
import com.github.guoyyit.portfolioattributionjava.domain.ValuationSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AttributionCalculatorTest {

    private final AttributionCalculator calculator =
            new AttributionCalculator(new ReturnCalculator());

    private static ValuationSnapshot snapshot(String date, Map<String, String> values) {
        var map = new java.util.HashMap<String, BigDecimal>();
        BigDecimal total = BigDecimal.ZERO;
        for (var entry : values.entrySet()) {
            BigDecimal value = new BigDecimal(entry.getValue());
            map.put(entry.getKey(), value);
            total = total.add(value);
        }
        return new ValuationSnapshot(LocalDate.parse(date), total, map);
    }

    @Test
    void brinsonEffectsSumToExcessReturnSinglePeriod() {
        // 期初：组合在 A 板块 70 / B 板块 30；基准 A 60 / B 40
        // 期末：组合 A 77(+10%) B 36(+20%)；基准 A 66(+10%) B 42(+5%)
        var p = new TreeMap<LocalDate, ValuationSnapshot>();
        p.put(LocalDate.parse("2026-01-01"),
                snapshot("2026-01-01", Map.of("A", "70", "B", "30")));
        p.put(LocalDate.parse("2026-01-31"),
                snapshot("2026-01-31", Map.of("A", "77", "B", "36")));
        var b = new TreeMap<LocalDate, ValuationSnapshot>();
        b.put(LocalDate.parse("2026-01-01"),
                snapshot("2026-01-01", Map.of("A", "60", "B", "40")));
        b.put(LocalDate.parse("2026-01-31"),
                snapshot("2026-01-31", Map.of("A", "66", "B", "42")));

        AttributionResult result = calculator.attribute(
                "p1", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"),
                p, b, List.of());

        BigDecimal threeSum = result.allocationEffect()
                .add(result.selectionEffect())
                .add(result.interactionEffect());
        assertTrue(result.excessReturn().subtract(threeSum).abs().doubleValue() < 1e-9,
                "三项合计必须等于超额收益: excess=" + result.excessReturn()
                        + " sum=" + threeSum);

        BigDecimal segmentSum = BigDecimal.ZERO;
        for (var segment : result.segments()) {
            BigDecimal rowSum = segment.allocationEffect()
                    .add(segment.selectionEffect())
                    .add(segment.interactionEffect());
            assertTrue(rowSum.subtract(segment.totalEffect()).abs().doubleValue() < 1e-12);
            segmentSum = segmentSum.add(segment.totalEffect());
        }
        assertTrue(segmentSum.subtract(result.excessReturn()).abs().doubleValue() < 1e-9,
                "分板块合计必须等于超额收益: " + segmentSum);
    }

    @Test
    void brinsonEffectsSumAcrossMultiplePeriodsWithCashFlow() {
        // 三个估值日，中间日有一笔 20 的现金流投入 A 板块
        var p = new TreeMap<LocalDate, ValuationSnapshot>();
        p.put(LocalDate.parse("2026-01-01"),
                snapshot("2026-01-01", Map.of("A", "60", "B", "40")));
        p.put(LocalDate.parse("2026-01-02"),
                snapshot("2026-01-02", Map.of("A", "86", "B", "44")));
        p.put(LocalDate.parse("2026-01-03"),
                snapshot("2026-01-03", Map.of("A", "94.6", "B", "48.4")));
        var bm = new TreeMap<LocalDate, ValuationSnapshot>();
        bm.put(LocalDate.parse("2026-01-01"),
                snapshot("2026-01-01", Map.of("A", "50", "B", "50")));
        bm.put(LocalDate.parse("2026-01-02"),
                snapshot("2026-01-02", Map.of("A", "55", "B", "50")));
        bm.put(LocalDate.parse("2026-01-03"),
                snapshot("2026-01-03", Map.of("A", "60.5", "B", "52.5")));

        CashFlow cashFlow = new CashFlow("cf1", LocalDate.parse("2026-01-02"),
                new BigDecimal("20"), "A");

        AttributionResult result = calculator.attribute(
                "p1", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-03"),
                p, bm, List.of(cashFlow));

        BigDecimal threeSum = result.allocationEffect()
                .add(result.selectionEffect())
                .add(result.interactionEffect());
        assertTrue(result.excessReturn().subtract(threeSum).abs().doubleValue() < 1e-9,
                "多期+现金流下三项合计必须等于超额收益: excess=" + result.excessReturn()
                        + " sum=" + threeSum);
    }
}

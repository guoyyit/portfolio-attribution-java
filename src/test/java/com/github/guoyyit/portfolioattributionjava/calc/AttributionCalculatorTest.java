package com.github.guoyyit.portfolioattributionjava.calc;

import com.github.guoyyit.portfolioattributionjava.error.ErrorCode;
import com.github.guoyyit.portfolioattributionjava.error.RejectionException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttributionCalculatorTest {

    private static final double EPS = 1e-12;

    private final AttributionCalculator calculator =
            new AttributionCalculator(new ReturnCalculator());

    @Test
    void singlePeriodMatchesTextbookBhb() {
        LocalDate d0 = LocalDate.of(2024, 1, 1);
        LocalDate d1 = LocalDate.of(2024, 1, 31);

        SectorSeries portfolio = series(
                new double[]{100, 104},
                Map.of("A", new double[]{60, 63}, "B", new double[]{40, 41}),
                List.of());
        SectorSeries benchmark = series(
                new double[]{100, 105},
                Map.of("A", new double[]{50, 52.5}, "B", new double[]{50, 52.5}),
                List.of());

        AttributionResult result = calculator.attribute(
                withDates(portfolio, d0, d1), withDates(benchmark, d0, d1));

        assertEquals(0.04, result.portfolioReturn(), EPS);
        assertEquals(0.05, result.benchmarkReturn(), EPS);
        assertEquals(-0.01, result.excessReturn(), EPS);
        // A: 0.1 * 0 = 0 ; B: -0.1 * 0 = 0
        assertEquals(0d, result.allocationEffect(), EPS);
        // rP_A = 5%, rP_B = 2.5% ; both benchmark sectors return 5%
        // A: 0.5 * 0 = 0 ; B: 0.5 * (2.5% - 5%) = -0.0125
        assertEquals(-0.0125, result.selectionEffect(), EPS);
        // B: (0.4 - 0.5) * (2.5% - 5%) = 0.0025
        assertEquals(0.0025, result.interactionEffect(), EPS);
        assertEquals(-0.01,
                result.allocationEffect() + result.selectionEffect()
                        + result.interactionEffect(), EPS);
    }

    @Test
    void threeEffectsSumExactlyToExcessReturnSinglePeriod() {
        LocalDate d0 = LocalDate.of(2024, 1, 1);
        LocalDate d1 = LocalDate.of(2024, 1, 31);

        SectorSeries portfolio = series(
                new double[]{100, 104},
                Map.of("A", new double[]{70, 77}, "B", new double[]{30, 27}),
                List.of());
        SectorSeries benchmark = series(
                new double[]{100, 105},
                Map.of("A", new double[]{50, 55}, "B", new double[]{50, 50}),
                List.of());

        AttributionResult result = calculator.attribute(
                withDates(portfolio, d0, d1), withDates(benchmark, d0, d1));

        // rP_A = 0.10, rP_B = -0.10 ; rB_A = 0.10, rB_B = 0 ; rB = 0.05
        // weights wP = (0.7, 0.3), wB = (0.5, 0.5)
        double expectedAllocation = 0.2 * 0.05 + (-0.2) * (-0.05);
        double expectedSelection = 0.5 * 0d + 0.5 * (-0.10);
        double expectedInteraction = 0.2 * 0d + (-0.2) * (-0.10);
        assertEquals(expectedAllocation, result.allocationEffect(), EPS);
        assertEquals(expectedSelection, result.selectionEffect(), EPS);
        assertEquals(expectedInteraction, result.interactionEffect(), EPS);
        assertEquals(result.excessReturn(),
                result.allocationEffect() + result.selectionEffect()
                        + result.interactionEffect(), EPS);
    }

    @Test
    void multiPeriodEffectsStillSumToExcessReturn() {
        LocalDate d0 = LocalDate.of(2024, 1, 1);
        LocalDate d1 = LocalDate.of(2024, 1, 15);
        LocalDate d2 = LocalDate.of(2024, 1, 31);

        SectorSeries portfolio = series3(
                new double[]{100, 110, 130},
                Map.of("A", new double[]{60, 66, 84},
                       "B", new double[]{40, 44, 46}),
                List.of(new CashFlowPoint(d1, 10, "A")));
        SectorSeries benchmark = series3(
                new double[]{100, 108, 120},
                Map.of("A", new double[]{50, 54, 66},
                       "B", new double[]{50, 54, 54}),
                List.of());

        AttributionResult result = calculator.attribute(portfolio, benchmark);

        double sum = result.allocationEffect() + result.selectionEffect()
                + result.interactionEffect();
        // 内部全精度（输出舍入之前）三项合计与超额收益逐位相等
        assertEquals(result.excessReturn(), sum, 0d);

        // 行业级三项之和与组合级三项合计一致，仅有独立求和路径的 ULP 级差异
        double sectorLevelSum = 0d;
        for (SectorAttribution sector : result.sectors()) {
            sectorLevelSum += sector.allocationEffect()
                    + sector.selectionEffect() + sector.interactionEffect();
        }
        assertEquals(sum, sectorLevelSum, 1e-15);
    }

    @Test
    void aggregateAttributionWhenNeitherSideHasSectorBreakdown() {
        LocalDate d0 = LocalDate.of(2024, 1, 1);
        LocalDate d1 = LocalDate.of(2024, 1, 31);

        SectorSeries portfolio = new SectorSeries(
                new LocalDate[]{d0, d1}, new double[]{100, 106},
                Map.of(), List.of());
        SectorSeries benchmark = new SectorSeries(
                new LocalDate[]{d0, d1}, new double[]{100, 102},
                Map.of(), List.of());

        AttributionResult result = calculator.attribute(portfolio, benchmark);

        assertEquals(0.06, result.portfolioReturn(), EPS);
        assertEquals(0.02, result.benchmarkReturn(), EPS);
        assertEquals(0.04, result.excessReturn(), EPS);
        assertEquals(0d, result.allocationEffect(), EPS);
        assertEquals(0d, result.interactionEffect(), EPS);
        assertEquals(0.04, result.selectionEffect(), EPS);
        assertEquals(0.04,
                result.allocationEffect() + result.selectionEffect()
                        + result.interactionEffect(), EPS);
        assertTrue(result.sectors().isEmpty());
    }

    @Test
    void aggregateAttributionOverMultiplePeriodsStillSumsExactly() {        LocalDate d0 = LocalDate.of(2024, 1, 1);
        LocalDate d1 = LocalDate.of(2024, 1, 15);
        LocalDate d2 = LocalDate.of(2024, 1, 31);

        SectorSeries portfolio = new SectorSeries(
                new LocalDate[]{d0, d1, d2},
                new double[]{100, 110, 130}, Map.of(),
                List.of(new CashFlowPoint(d1, 10, "CASH")));
        SectorSeries benchmark = new SectorSeries(
                new LocalDate[]{d0, d1, d2},
                new double[]{100, 105, 110}, Map.of(), List.of());

        AttributionResult result = calculator.attribute(portfolio, benchmark);

        double sum = result.allocationEffect() + result.selectionEffect()
                + result.interactionEffect();
        assertEquals(result.excessReturn(), sum, EPS);
        assertEquals(0d, result.allocationEffect(), EPS);
        assertEquals(0d, result.interactionEffect(), EPS);
    }

    @Test
    void rejectsWhenOnlyOneSideHasSectorBreakdown() {
        LocalDate d0 = LocalDate.of(2024, 1, 1);
        LocalDate d1 = LocalDate.of(2024, 1, 31);

        SectorSeries withSectors = new SectorSeries(
                new LocalDate[]{d0, d1}, new double[]{100, 104},
                Map.of("A", new double[]{60, 63}, "B", new double[]{40, 41}),
                List.of());
        SectorSeries withoutSectors = new SectorSeries(
                new LocalDate[]{d0, d1}, new double[]{100, 105},
                Map.of(), List.of());

        RejectionException first = assertThrows(RejectionException.class,
                () -> calculator.attribute(withSectors, withoutSectors));
        assertEquals(ErrorCode.SECTOR_BREAKDOWN_MISSING, first.getErrorCode());
        RejectionException second = assertThrows(RejectionException.class,
                () -> calculator.attribute(withoutSectors, withSectors));
        assertEquals(ErrorCode.SECTOR_BREAKDOWN_MISSING, second.getErrorCode());
        // 拒绝信息只取决于数据本身，不取决于组合/基准参数顺序
        assertTrue(first.getMessage().contains("benchmark side"));
        assertTrue(second.getMessage().contains("portfolio side"));
    }

    @Test
    void rejectsWhenSectorBreakdownIsMissingAtAnIntermediateDate() {
        LocalDate d0 = LocalDate.of(2024, 1, 1);
        LocalDate d1 = LocalDate.of(2024, 1, 15);
        LocalDate d2 = LocalDate.of(2024, 1, 31);

        // 组合行业 A 在 d1 没有拆分记录（数组按值填 0，但存在性标记为 false）
        SectorSeries portfolio = new SectorSeries(
                new LocalDate[]{d0, d1, d2},
                new double[]{100, 110, 130},
                new java.util.TreeMap<>(Map.of(
                        "A", new double[]{60, 0, 84},
                        "B", new double[]{40, 0, 46})),
                List.of(new CashFlowPoint(d1, 10, "A")),
                Map.of(
                        "A", new boolean[]{true, false, true},
                        "B", new boolean[]{true, false, true}));
        SectorSeries benchmark = series3(
                new double[]{100, 108, 120},
                Map.of("A", new double[]{50, 54, 66},
                       "B", new double[]{50, 54, 54}),
                List.of());

        RejectionException ex = assertThrows(RejectionException.class,
                () -> calculator.attribute(portfolio, benchmark));
        assertEquals(ErrorCode.SECTOR_BREAKDOWN_MISSING, ex.getErrorCode());
        assertTrue(ex.getMessage().contains(d1.toString()));
    }

    private SectorSeries series(double[] totals,
                                Map<String, double[]> sectors,
                                List<CashFlowPoint> flows) {
        return withDates(new SectorSeries(null, totals, sectors, flows),
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31));
    }

    private SectorSeries withDates(SectorSeries input, LocalDate... dates) {
        return new SectorSeries(dates, input.totals(), input.sectorValues(),
                input.flows());
    }

    private SectorSeries series3(double[] totals,
                                 Map<String, double[]> sectors,
                                 List<CashFlowPoint> flows) {
        return new SectorSeries(
                new LocalDate[]{
                        LocalDate.of(2024, 1, 1),
                        LocalDate.of(2024, 1, 15),
                        LocalDate.of(2024, 1, 31)},
                totals, sectors, flows);
    }
}

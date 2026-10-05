package com.github.guoyyit.portfolioattributionjava.calc;

import com.github.guoyyit.portfolioattributionjava.error.ErrorCode;
import com.github.guoyyit.portfolioattributionjava.error.RejectionException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertEquals(result.excessReturn(), sum, 1e-10);
    }

    @Test
    void noSectorBreakdownOnEitherSideStillSatisfiesIdentity() {
        SectorSeries portfolio = series3(
                new double[]{100, 110, 121}, Map.of(), List.of());
        SectorSeries benchmark = series3(
                new double[]{100, 105, 108}, Map.of(), List.of());

        AttributionResult result = calculator.attribute(portfolio, benchmark);

        assertEquals(0d, result.allocationEffect(), EPS);
        assertEquals(result.excessReturn(), result.selectionEffect(), EPS);
        assertEquals(0d, result.interactionEffect(), EPS);
        assertEquals(0d, result.excessReturn()
                - (result.allocationEffect() + result.selectionEffect()
                + result.interactionEffect()), EPS);
        assertEquals(0, result.sectors().size());
        // sanity: excess is non-zero, so the identity is not vacuous
        assertEquals(1.1 * 1.1 - 1.05 * (108 / 105d), result.excessReturn(), EPS);
    }

    @Test
    void oneSidedSectorBreakdownDegradesToTotalLevelWithIdentity() {
        SectorSeries portfolio = series3(
                new double[]{100, 110, 131},
                Map.of("A", new double[]{60, 66, 86},
                       "B", new double[]{40, 44, 45}),
                List.of(new CashFlowPoint(LocalDate.of(2024, 1, 20), 10, "A")));
        SectorSeries benchmark = series3(
                new double[]{100, 108, 120}, Map.of(), List.of());

        AttributionResult result = calculator.attribute(portfolio, benchmark);

        assertEquals(0d, result.allocationEffect(), EPS);
        assertEquals(result.excessReturn(), result.selectionEffect(), EPS);
        assertEquals(0d, result.interactionEffect(), EPS);
        assertEquals(0, result.sectors().size());
        // TWR_P = 1.1 * (1 + (131-110-10)/110) - 1 = 0.21; TWR_B = 1.08*(120/108)-1 = 0.2
        assertEquals(0.01, result.excessReturn(), 1e-12);
    }

    @Test
    void mismatchedNonEmptySectorSetsAreRejectedStably() {
        SectorSeries portfolio = series3(
                new double[]{100, 110, 121},
                Map.of("A", new double[]{60, 66, 72.6},
                       "B", new double[]{40, 44, 48.4}),
                List.of());
        SectorSeries benchmark = series3(
                new double[]{100, 105, 108},
                Map.of("A", new double[]{50, 52.5, 54},
                       "C", new double[]{50, 52.5, 54}),
                List.of());

        RejectionException ex = assertThrows(RejectionException.class,
                () -> calculator.attribute(portfolio, benchmark));
        assertEquals(ErrorCode.SECTOR_SET_MISMATCH, ex.getErrorCode());
    }

    @Test
    void cashFlowOnDateWithoutSnapshotKeepsAttributionIdentity() {
        LocalDate dMid = LocalDate.of(2024, 1, 20); // no valuation snapshot here
        SectorSeries portfolio = series3(
                new double[]{100, 110, 131},
                Map.of("A", new double[]{60, 66, 86},
                       "B", new double[]{40, 44, 45}),
                List.of(new CashFlowPoint(dMid, 10, "A")));
        SectorSeries benchmark = series3(
                new double[]{100, 108, 120},
                Map.of("A", new double[]{50, 54, 66},
                       "B", new double[]{50, 54, 54}),
                List.of());

        AttributionResult result = calculator.attribute(portfolio, benchmark);

        // TWR_P = 0.21, TWR_B = 0.2 -> excess 0.01 must be fully explained
        assertEquals(0.01, result.excessReturn(), 1e-12);
        double sum = result.allocationEffect() + result.selectionEffect()
                + result.interactionEffect();
        assertEquals(result.excessReturn(), sum, 1e-10);
        // sector rows add up to the totals as well
        double sectorSum = 0d;
        for (SectorAttribution sector : result.sectors()) {
            sectorSum += sector.allocationEffect() + sector.selectionEffect()
                    + sector.interactionEffect();
        }
        assertEquals(result.excessReturn(), sectorSum, 1e-10);
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

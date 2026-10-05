package com.github.guoyyit.portfolioattributionjava.calc;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReturnCalculatorTest {

    private static final double EPS = 1e-12;

    private final ReturnCalculator calculator = new ReturnCalculator();

    @Test
    void timeWeightedReturnCompoundsAcrossCashFlowDates() {
        LocalDate d0 = LocalDate.of(2024, 1, 1);
        LocalDate d1 = LocalDate.of(2024, 1, 8);
        LocalDate d2 = LocalDate.of(2024, 1, 15);
        double[] totals = {100, 110, 126.5};
        List<CashFlowPoint> flows = List.of(new CashFlowPoint(d1, 5, "CASH"));
        SectorSeries series = new SectorSeries(
                new LocalDate[]{d0, d1, d2}, totals, Map.of(), flows);

        double twr = calculator.timeWeightedReturn(series);

        // r1 = (110-100-5)/100 = 0.05; r2 = (126.5-110)/110 = 0.15
        assertEquals(1.05 * 1.15 - 1, twr, EPS);
    }

    @Test
    void wholeIntervalEqualsCompoundingOfAdjacentSubIntervals() {
        LocalDate d0 = LocalDate.of(2024, 1, 1);
        LocalDate d1 = LocalDate.of(2024, 1, 8);
        LocalDate d2 = LocalDate.of(2024, 1, 15);
        List<CashFlowPoint> flows = List.of(new CashFlowPoint(d1, 10, "CASH"));
        SectorSeries full = new SectorSeries(
                new LocalDate[]{d0, d1, d2}, new double[]{100, 108, 120},
                Map.of(), flows);

        double whole = calculator.timeWeightedReturn(full);
        double first = calculator.timeWeightedReturn(new SectorSeries(
                new LocalDate[]{d0, d1}, new double[]{100, 108},
                Map.of(), flows));
        double second = calculator.timeWeightedReturn(new SectorSeries(
                new LocalDate[]{d1, d2}, new double[]{108, 120},
                Map.of(), List.of()));

        assertEquals(whole, (1 + first) * (1 + second) - 1, EPS);
    }

    @Test
    void moneyWeightedReturnFollowsModifiedDietz() {
        LocalDate d0 = LocalDate.of(2024, 1, 1);
        LocalDate dm = LocalDate.of(2024, 1, 8);
        LocalDate dn = LocalDate.of(2024, 1, 15);
        List<CashFlowPoint> flows = List.of(new CashFlowPoint(dm, 10, "CASH"));
        SectorSeries series = new SectorSeries(
                new LocalDate[]{d0, dm, dn}, new double[]{100, 110, 116},
                Map.of(), flows);

        double mwr = calculator.moneyWeightedReturn(series);

        // gain = 116 - 100 - 10 = 6; weight = 7/14; denominator = 105
        assertEquals(6d / 105d, mwr, EPS);
    }

    @Test
    void cashFlowAtStartIsAlreadyIncludedInBeginningValue() {
        LocalDate d0 = LocalDate.of(2024, 1, 1);
        LocalDate d1 = LocalDate.of(2024, 1, 31);
        List<CashFlowPoint> startFlow = List.of(new CashFlowPoint(d0, 20, "CASH"));
        SectorSeries series = new SectorSeries(
                new LocalDate[]{d0, d1}, new double[]{120, 132},
                Map.of(), startFlow);

        // TWR sub-return: (132-120-0)/120 = 0.1 (start flow excluded from window)
        assertEquals(0.10, calculator.timeWeightedReturn(series), EPS);
        // MWR also excludes start flow from weighting
        assertEquals(0.10, calculator.moneyWeightedReturn(series), EPS);
    }

    @Test
    void netCashFlowsOnSameDateDoNotDoubleCount() {
        LocalDate d0 = LocalDate.of(2024, 1, 1);
        LocalDate d1 = LocalDate.of(2024, 1, 2);
        LocalDate d2 = LocalDate.of(2024, 1, 3);
        List<CashFlowPoint> flows = List.of(
                new CashFlowPoint(d1, 8, "CASH"),
                new CashFlowPoint(d1, -3, "CASH"));
        SectorSeries series = new SectorSeries(
                new LocalDate[]{d0, d1, d2}, new double[]{100, 105, 107.1},
                Map.of(), flows);

        // net flow 5: r1 = 0, r2 = 107.1/105 - 1 = 0.02
        assertEquals(0.02, calculator.timeWeightedReturn(series), EPS);
    }

    @Test
    void cashFlowOnDateWithoutSnapshotIsCountedByBothMeasures() {
        LocalDate d0 = LocalDate.of(2024, 1, 1);
        LocalDate d1 = LocalDate.of(2024, 1, 8); // no valuation snapshot here
        LocalDate d2 = LocalDate.of(2024, 1, 15);
        List<CashFlowPoint> flows = List.of(new CashFlowPoint(d1, 5, "CASH"));
        SectorSeries series = new SectorSeries(
                new LocalDate[]{d0, d2}, new double[]{100, 110},
                Map.of(), flows);

        // TWR: flow joins the containing sub-interval (d0, d2]
        assertEquals((110 - 100 - 5) / 100d,
                calculator.timeWeightedReturn(series), EPS);
        // MWR: flow weighted by its actual date, weight = 7/14
        assertEquals(5d / (100d + 5d * 7d / 14d),
                calculator.moneyWeightedReturn(series), EPS);
    }

    @Test
    void twrCompoundsAcrossSubIntervalsWithMidIntervalCashFlow() {
        LocalDate d0 = LocalDate.of(2024, 1, 1);
        LocalDate d1 = LocalDate.of(2024, 1, 8);
        LocalDate dMid = LocalDate.of(2024, 1, 10); // no valuation snapshot here
        LocalDate d2 = LocalDate.of(2024, 1, 15);
        List<CashFlowPoint> flows = List.of(new CashFlowPoint(dMid, 6, "CASH"));
        SectorSeries full = new SectorSeries(
                new LocalDate[]{d0, d1, d2}, new double[]{100, 108, 120},
                Map.of(), flows);

        double whole = calculator.timeWeightedReturn(full);
        double first = calculator.timeWeightedReturn(new SectorSeries(
                new LocalDate[]{d0, d1}, new double[]{100, 108},
                Map.of(), flows));
        double second = calculator.timeWeightedReturn(new SectorSeries(
                new LocalDate[]{d1, d2}, new double[]{108, 120},
                Map.of(), flows));

        // r1 = 0.08; r2 = (120-108-6)/108; flow belongs to (d1, d2] in both views
        assertEquals(whole, (1 + first) * (1 + second) - 1, EPS);
        assertEquals(1.08 * (1 + 6d / 108d) - 1, whole, EPS);
    }
}

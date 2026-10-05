package com.github.guoyyit.portfolioattributionjava.calc;

import com.github.guoyyit.portfolioattributionjava.error.ErrorCode;
import com.github.guoyyit.portfolioattributionjava.error.RejectionException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void interiorCashFlowWithoutSnapshotIsRejectedByBothBases() {
        LocalDate d0 = LocalDate.of(2024, 1, 1);
        LocalDate dFlow = LocalDate.of(2024, 1, 5);
        LocalDate d1 = LocalDate.of(2024, 1, 31);
        List<CashFlowPoint> flows = List.of(new CashFlowPoint(dFlow, 10, "CASH"));
        SectorSeries series = new SectorSeries(
                new LocalDate[]{d0, d1}, new double[]{100, 120},
                Map.of(), flows);

        // TWR 无法在 dFlow 切分子区间；旧实现会忽略该现金流，而 MWR 会计入它。
        RejectionException twrEx = assertThrows(RejectionException.class,
                () -> calculator.timeWeightedReturn(series));
        assertEquals(ErrorCode.CASH_FLOW_WITHOUT_VALUATION,
                twrEx.getErrorCode());
        // 两种口径必须给出一致、不互相矛盾的处理：同样拒绝，同一稳定错误码。
        RejectionException mwrEx = assertThrows(RejectionException.class,
                () -> calculator.moneyWeightedReturn(series));
        assertEquals(ErrorCode.CASH_FLOW_WITHOUT_VALUATION,
                mwrEx.getErrorCode());
        assertEquals(twrEx.getMessage(), mwrEx.getMessage());
    }

    @Test
    void rejectionIsStableRegardlessOfFlowOrder() {
        LocalDate d0 = LocalDate.of(2024, 1, 1);
        LocalDate dFlow = LocalDate.of(2024, 1, 6);
        LocalDate d1 = LocalDate.of(2024, 1, 31);
        LocalDate[] dates = {d0, d1};
        double[] totals = {100, 120};

        List<CashFlowPoint> ordered = new ArrayList<>(List.of(
                new CashFlowPoint(dFlow, 4, "A"),
                new CashFlowPoint(dFlow, 6, "B")));
        List<CashFlowPoint> shuffled = new ArrayList<>(List.of(
                new CashFlowPoint(dFlow, 6, "B"),
                new CashFlowPoint(dFlow, 4, "A")));

        RejectionException first = assertThrows(RejectionException.class,
                () -> calculator.timeWeightedReturn(
                        new SectorSeries(dates, totals, Map.of(), ordered)));
        RejectionException second = assertThrows(RejectionException.class,
                () -> calculator.timeWeightedReturn(
                        new SectorSeries(dates, totals, Map.of(), shuffled)));
        assertEquals(first.getErrorCode(), second.getErrorCode());
        assertEquals(first.getMessage(), second.getMessage());
    }

    @Test
    void netZeroFlowOnSnapshotLessDateIsAcceptedByBothBases() {
        // 同日多笔现金流净额为 0 时，对 TWR 与 MWR 均无经济影响，允许无快照。
        LocalDate d0 = LocalDate.of(2024, 1, 1);
        LocalDate dFlow = LocalDate.of(2024, 1, 5);
        LocalDate d1 = LocalDate.of(2024, 1, 31);
        List<CashFlowPoint> flows = List.of(
                new CashFlowPoint(dFlow, 10, "A"),
                new CashFlowPoint(dFlow, -10, "A"));
        SectorSeries series = new SectorSeries(
                new LocalDate[]{d0, d1}, new double[]{100, 110},
                Map.of(), flows);

        assertEquals(0.10, calculator.timeWeightedReturn(series), EPS);
        assertEquals(0.10, calculator.moneyWeightedReturn(series), EPS);
    }

    @Test
    void flowOnInteriorDateWithoutSnapshotIsRejectedAndBoundariesAreHandled() {
        LocalDate d0 = LocalDate.of(2024, 1, 1);
        LocalDate dMid = LocalDate.of(2024, 1, 15);
        LocalDate d1 = LocalDate.of(2024, 1, 31);
        LocalDate dAfter = LocalDate.of(2024, 2, 1);

        // 严格位于 (d0, dn] 的无快照现金流必须拒绝（两种口径同一错误码）
        SectorSeries midNoSnapshot = new SectorSeries(
                new LocalDate[]{d0, d1}, new double[]{100, 110},
                Map.of(), List.of(new CashFlowPoint(dMid, 10, "A")));
        RejectionException ex = assertThrows(RejectionException.class,
                () -> calculator.timeWeightedReturn(midNoSnapshot));
        assertEquals(ErrorCode.CASH_FLOW_WITHOUT_VALUATION, ex.getErrorCode());
        assertEquals(ex.getErrorCode(), assertThrows(RejectionException.class,
                () -> calculator.moneyWeightedReturn(midNoSnapshot)).getErrorCode());

        // 起始日现金流视为已包含在 V0 中：不报错，不影响结果
        SectorSeries atStart = new SectorSeries(
                new LocalDate[]{d0, d1}, new double[]{110, 121},
                Map.of(), List.of(new CashFlowPoint(d0, 10, "A")));
        assertEquals(0.10, calculator.timeWeightedReturn(atStart), EPS);
        assertEquals(0.10, calculator.moneyWeightedReturn(atStart), EPS);

        // 区间外现金流不参与任何一种口径
        SectorSeries outside = new SectorSeries(
                new LocalDate[]{d0, d1}, new double[]{100, 110},
                Map.of(), List.of(new CashFlowPoint(dAfter, 10, "A")));
        assertEquals(0.10, calculator.timeWeightedReturn(outside), EPS);
        assertEquals(0.10, calculator.moneyWeightedReturn(outside), EPS);
    }

    @Test
    void wholeIntervalMatchesCompoundingAcrossThreePointsAndArbitrarySplit() {
        LocalDate d0 = LocalDate.of(2024, 1, 1);
        LocalDate d1 = LocalDate.of(2024, 1, 8);
        LocalDate d2 = LocalDate.of(2024, 1, 22);
        LocalDate d3 = LocalDate.of(2024, 1, 31);
        List<CashFlowPoint> flows = List.of(
                new CashFlowPoint(d1, 5, "CASH"),
                new CashFlowPoint(d2, 7, "CASH"));
        SectorSeries full = new SectorSeries(
                new LocalDate[]{d0, d1, d2, d3},
                new double[]{100, 108, 120, 130}, Map.of(), flows);

        double whole = calculator.timeWeightedReturn(full);

        double r01 = calculator.timeWeightedReturn(new SectorSeries(
                new LocalDate[]{d0, d1}, new double[]{100, 108},
                Map.of(), List.of(new CashFlowPoint(d1, 5, "CASH"))));
        double r12 = calculator.timeWeightedReturn(new SectorSeries(
                new LocalDate[]{d1, d2}, new double[]{108, 120},
                Map.of(), List.of(new CashFlowPoint(d2, 7, "CASH"))));
        double r23 = calculator.timeWeightedReturn(new SectorSeries(
                new LocalDate[]{d2, d3}, new double[]{120, 130},
                Map.of(), List.of()));
        double compounded = (1 + r01) * (1 + r12) * (1 + r23) - 1;

        assertEquals(whole, compounded, EPS);

        // 任意相邻切分（在估值日期上切）也一致
        double left = calculator.timeWeightedReturn(new SectorSeries(
                new LocalDate[]{d0, d1, d2}, new double[]{100, 108, 120},
                Map.of(), List.of(new CashFlowPoint(d1, 5, "CASH"),
                        new CashFlowPoint(d2, 7, "CASH"))));
        double right = calculator.timeWeightedReturn(new SectorSeries(
                new LocalDate[]{d2, d3}, new double[]{120, 130},
                Map.of(), List.of()));
        assertEquals(whole, (1 + left) * (1 + right) - 1, EPS);
    }
}

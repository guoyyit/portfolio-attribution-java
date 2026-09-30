package com.github.guoyyit.portfolioattributionjava.tests;

import com.github.guoyyit.portfolioattributionjava.calc.ReturnCalculator;
import com.github.guoyyit.portfolioattributionjava.domain.CashFlow;
import com.github.guoyyit.portfolioattributionjava.domain.ValuationSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReturnCalculatorTest {

    private final ReturnCalculator calculator = new ReturnCalculator();
    private static final String SEG = "A";

    private static ValuationSnapshot snapshot(String date, String value) {
        return new ValuationSnapshot(LocalDate.parse(date), new BigDecimal(value),
                Map.of(SEG, new BigDecimal(value)));
    }

    private static TreeMap<LocalDate, ValuationSnapshot> valuations(
            ValuationSnapshot... snapshots) {
        TreeMap<LocalDate, ValuationSnapshot> map = new TreeMap<>();
        for (ValuationSnapshot snapshot : snapshots) {
            map.put(snapshot.date(), snapshot);
        }
        return map;
    }

    private static CashFlow flow(String id, String date, String amount) {
        return new CashFlow(id, LocalDate.parse(date), new BigDecimal(amount), SEG);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    @Test
    void timeWeightedReturnWithoutCashFlows() {
        var map = valuations(snapshot("2026-01-01", "100"),
                snapshot("2026-01-02", "105"),
                snapshot("2026-01-03", "110.25"));
        BigDecimal twr = calculator.timeWeightedReturn(map, List.of());
        assertEquals(0, bd("0.1025").compareTo(twr), "两日各涨 5%，复合为 10.25%");
    }

    @Test
    void subPeriodReturnsCompoundToWholePeriod() {
        var map = valuations(snapshot("2026-01-01", "100"),
                snapshot("2026-01-02", "110"),
                snapshot("2026-01-03", "99"),
                snapshot("2026-01-04", "108.9"));
        var flows = List.of(flow("cf1", "2026-01-02", "20"),
                flow("cf2", "2026-01-03", "-10"));

        BigDecimal direct = calculator.timeWeightedReturn(map, flows);
        List<BigDecimal> subs = calculator.subPeriodReturns(map, flows);
        BigDecimal compounded = BigDecimal.ONE;
        for (BigDecimal sub : subs) {
            compounded = compounded.multiply(BigDecimal.ONE.add(sub));
        }
        compounded = compounded.subtract(BigDecimal.ONE)
                .setScale(10, RoundingMode.HALF_UP);

        // 子区间：(110-20)/100-1=-0.1，(99-(-10))/110-1=-0.00909..., 108.9/99-1=0.1
        assertEquals(3, subs.size());
        assertEquals(0, bd("-0.1").compareTo(subs.get(0)));
        assertTrue(direct.subtract(compounded).abs().doubleValue() < 1e-9,
                "直接计算与子区间复合必须一致, direct=" + direct + " compounded=" + compounded);

        // 分段复合：[d1,d2] 与 [d2,d4] 各自计算后再复合，必须等于整段
        var firstPart = valuations(snapshot("2026-01-01", "100"),
                snapshot("2026-01-02", "110"));
        var secondPart = valuations(snapshot("2026-01-02", "110"),
                snapshot("2026-01-03", "99"),
                snapshot("2026-01-04", "108.9"));
        BigDecimal r1 = calculator.timeWeightedReturn(firstPart, List.of(flow("cf1", "2026-01-02", "20")));
        BigDecimal r2 = calculator.timeWeightedReturn(secondPart, List.of(flow("cf2", "2026-01-03", "-10")));
        BigDecimal splitCompounded = BigDecimal.ONE.add(r1).multiply(BigDecimal.ONE.add(r2))
                .subtract(BigDecimal.ONE).setScale(10, RoundingMode.HALF_UP);
        assertTrue(direct.subtract(splitCompounded).abs().doubleValue() < 1e-9,
                "任意切分的子区间复合必须等于整段: " + splitCompounded);
    }

    @Test
    void moneyWeightedReturnIsIndependentFromTimeWeighted() {
        var map = valuations(snapshot("2026-01-01", "100"),
                snapshot("2026-07-01", "130"),
                snapshot("2026-12-31", "140"));
        var flows = List.of(flow("cf1", "2026-07-01", "10"));

        BigDecimal twr = calculator.timeWeightedReturn(map, flows);
        BigDecimal mwr = calculator.moneyWeightedReturn(map, flows);

        // TWR: (130-10)/100 - 1 = 0.2, 140/130 - 1 ≈ 0.076923，复合
        assertEquals(0, bd("0.2923076923").compareTo(twr),
                "TWR 与现金流规模无关, got " + twr);
        // MWR 由 IRR 决定，必须独立成立；用 NPV 回代验证
        double rate = mwr.doubleValue();
        double npv = -100 - 10 / Math.pow(1 + rate, 181.0 / 365.0)
                + 140 / Math.pow(1 + rate, 364.0 / 365.0);
        assertTrue(Math.abs(npv) < 1e-6, "MWR 必须使 NPV≈0, npv=" + npv);
    }

    @Test
    void moneyWeightedReturnWithEndDateCashFlowNotDoubleCounted() {
        // 末日现金流 20 已包含在收盘价值 120 中：等效于无中间流、期末价值 120
        var map = valuations(snapshot("2026-01-01", "100"),
                snapshot("2026-12-31", "120"));
        var flows = List.of(flow("cfEnd", "2026-12-31", "20"));
        BigDecimal mwr = calculator.moneyWeightedReturn(map, flows);
        // 364 天年化：(1+r)^(364/365)=1.2 → r≈0.2006012106
        assertEquals(0, new BigDecimal("0.2006012106").compareTo(mwr),
                "末日现金流不得重复计入, got " + mwr);
    }

    @Test
    void zeroGrowthPeriods() {
        var map = valuations(snapshot("2026-01-01", "100"),
                snapshot("2026-01-02", "100"),
                snapshot("2026-01-03", "100"));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                calculator.timeWeightedReturn(map, List.of())));
    }
}

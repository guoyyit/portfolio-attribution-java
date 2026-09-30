package com.github.guoyyit.portfolioattributionjava.calc;

import java.time.LocalDate;
import java.util.Map;

public record SectorSeries(
        LocalDate[] dates,
        double[] totals,
        Map<String, double[]> sectorValues,
        java.util.List<CashFlowPoint> flows
) {
}

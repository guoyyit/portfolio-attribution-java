package com.github.guoyyit.portfolioattributionjava.calc;

import java.util.List;

public record AttributionResult(
        double portfolioReturn,
        double benchmarkReturn,
        double excessReturn,
        double allocationEffect,
        double selectionEffect,
        double interactionEffect,
        List<SectorAttribution> sectors
) {
}

package com.github.guoyyit.portfolioattributionjava.calc;

public record SectorAttribution(
        String sectorId,
        double portfolioWeight,
        double benchmarkWeight,
        double portfolioSectorReturn,
        double benchmarkSectorReturn,
        double allocationEffect,
        double selectionEffect,
        double interactionEffect
) {
}

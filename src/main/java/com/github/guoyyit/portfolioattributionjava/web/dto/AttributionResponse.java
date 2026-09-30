package com.github.guoyyit.portfolioattributionjava.web.dto;

import java.util.List;

public record AttributionResponse(
        String portfolioId,
        String benchmarkId,
        String startDate,
        String endDate,
        double portfolioReturn,
        double benchmarkReturn,
        double excessReturn,
        double allocationEffect,
        double selectionEffect,
        double interactionEffect,
        List<SectorAttributionDto> sectors
) {

    public record SectorAttributionDto(
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
}

package com.github.guoyyit.portfolioattributionjava.web.dto;

import java.time.LocalDate;
import java.util.List;

public record ValuationRequest(
        String portfolioId,
        LocalDate date,
        Double totalValue,
        List<SectorValueDto> sectors
) {

    public record SectorValueDto(String sectorId, Double marketValue) {
    }
}

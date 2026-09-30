package com.github.guoyyit.portfolioattributionjava.web.dto;

import java.time.LocalDate;

public record CashFlowRequest(
        String portfolioId,
        String externalFlowId,
        LocalDate date,
        Double amount,
        String sectorId
) {
}

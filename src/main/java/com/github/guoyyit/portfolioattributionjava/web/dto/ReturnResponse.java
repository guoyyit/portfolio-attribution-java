package com.github.guoyyit.portfolioattributionjava.web.dto;

public record ReturnResponse(
        String portfolioId,
        String startDate,
        String endDate,
        double timeWeightedReturn,
        double moneyWeightedReturn
) {
}

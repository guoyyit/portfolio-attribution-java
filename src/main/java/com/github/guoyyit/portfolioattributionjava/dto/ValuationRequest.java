package com.github.guoyyit.portfolioattributionjava.dto;

import java.math.BigDecimal;
import java.util.Map;

/** 估值快照录入请求。kind 取 PORTFOLIO / BENCHMARK。 */
public record ValuationRequest(
        String kind,
        String date,
        BigDecimal totalValue,
        Map<String, BigDecimal> segments
) {
}

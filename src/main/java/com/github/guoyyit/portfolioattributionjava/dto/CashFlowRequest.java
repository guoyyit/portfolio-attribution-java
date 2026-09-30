package com.github.guoyyit.portfolioattributionjava.dto;

import java.math.BigDecimal;

/** 外部现金流录入请求。 */
public record CashFlowRequest(
        String id,
        String date,
        BigDecimal amount,
        String segment
) {
}

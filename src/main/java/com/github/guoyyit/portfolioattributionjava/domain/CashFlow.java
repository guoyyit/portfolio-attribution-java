package com.github.guoyyit.portfolioattributionjava.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 外部现金流。amount 正为投入、负为取出；segment 为现金流投入/取出的板块。
 */
public record CashFlow(
        String id,
        LocalDate date,
        BigDecimal amount,
        String segment
) {
}

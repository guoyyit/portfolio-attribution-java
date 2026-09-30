package com.github.guoyyit.portfolioattributionjava.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 按日到达的估值快照。segments 为行业/板块代码 -> 该板块资产市值，
 * 全部板块之和必须等于 totalValue。
 */
public record ValuationSnapshot(
        LocalDate date,
        BigDecimal totalValue,
        Map<String, BigDecimal> segments
) {
}

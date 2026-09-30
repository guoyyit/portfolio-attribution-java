package com.github.guoyyit.portfolioattributionjava.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 超额收益归因结果（BHB 分解，按子区间复合）。
 * excessReturn = portfolioReturn - benchmarkReturn
 *                = allocation + selection + interaction（在舍入误差内）。
 */
public record AttributionResult(
        String portfolioId,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal portfolioReturn,
        BigDecimal benchmarkReturn,
        BigDecimal excessReturn,
        BigDecimal allocationEffect,
        BigDecimal selectionEffect,
        BigDecimal interactionEffect,
        List<SegmentAttribution> segments
) {
}

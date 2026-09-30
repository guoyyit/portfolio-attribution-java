package com.github.guoyyit.portfolioattributionjava.domain;

import java.math.BigDecimal;

/** 单个板块的配置效应 / 选择效应 / 交互效应。 */
public record SegmentAttribution(
        String segment,
        BigDecimal allocationEffect,
        BigDecimal selectionEffect,
        BigDecimal interactionEffect,
        BigDecimal totalEffect
) {
}

package com.github.guoyyit.portfolioattributionjava.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 区间收益结果。subReturns 为按估值日切分的子区间时间加权收益，
 * 任意区间的 TWR 必须可由子区间结果复合得出。
 */
public record ReturnResult(
        String portfolioId,
        Kind kind,
        LocalDate startDate,
        LocalDate endDate,
        ReturnKind returnKind,
        BigDecimal value,
        List<BigDecimal> subReturns
) {
}

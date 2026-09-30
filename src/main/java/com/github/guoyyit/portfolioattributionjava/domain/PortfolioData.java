package com.github.guoyyit.portfolioattributionjava.domain;

import java.util.List;
import java.util.Map;
import java.time.LocalDate;

/** 单个组合及其基准的不可变数据视图，供计算与查询使用。 */
public record PortfolioData(
        String portfolioId,
        Map<LocalDate, ValuationSnapshot> portfolioValuations,
        Map<LocalDate, ValuationSnapshot> benchmarkValuations,
        List<CashFlow> portfolioCashFlows
) {
}

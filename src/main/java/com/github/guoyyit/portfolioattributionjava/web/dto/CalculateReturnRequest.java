package com.github.guoyyit.portfolioattributionjava.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 一次性收益计算请求：显式给出估值序列与区间内现金流，不访问已持久化数据。
 * 用于对现金流窗口、重复/乱序数据等输入给出稳定拒绝。
 */
public record CalculateReturnRequest(
        List<ValuationPointDto> valuations,
        List<CashFlowPointDto> cashFlows
) {

    public record ValuationPointDto(LocalDate date, Double totalValue) {
    }

    public record CashFlowPointDto(LocalDate date, Double amount) {
    }
}

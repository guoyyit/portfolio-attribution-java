package com.github.guoyyit.portfolioattributionjava.service;

import com.github.guoyyit.portfolioattributionjava.calc.AttributionCalculator;
import com.github.guoyyit.portfolioattributionjava.calc.ReturnCalculator;
import com.github.guoyyit.portfolioattributionjava.domain.AttributionResult;
import com.github.guoyyit.portfolioattributionjava.domain.CashFlow;
import com.github.guoyyit.portfolioattributionjava.domain.Kind;
import com.github.guoyyit.portfolioattributionjava.domain.PortfolioData;
import com.github.guoyyit.portfolioattributionjava.domain.ReturnKind;
import com.github.guoyyit.portfolioattributionjava.domain.ReturnResult;
import com.github.guoyyit.portfolioattributionjava.domain.ValuationSnapshot;
import com.github.guoyyit.portfolioattributionjava.dto.CashFlowRequest;
import com.github.guoyyit.portfolioattributionjava.dto.ValuationRequest;
import com.github.guoyyit.portfolioattributionjava.store.PortfolioStore;
import com.github.guoyyit.portfolioattributionjava.web.ApiException;
import com.github.guoyyit.portfolioattributionjava.web.ErrorCode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 录入与查询的应用服务：统一校验、计算编排。
 *
 * <p>语义约定（与 docs/attribution.md 一致）：</p>
 * <ul>
 *   <li>估值为当日收盘口径（已包含当日外部现金流）；同一 (组合/基准, 日期)
 *       的再次提交按事后修正处理，覆盖旧值。</li>
 *   <li>现金流按 id 幂等：相同 id 再次提交即修正（金额/日期/板块以最新提交为准），
 *       不会造成重复计算。</li>
 *   <li>查询区间 [startDate, endDate] 的 TWR 只使用 (startDate, endDate] 内的现金流；
 *       现金流日期早于或等于 startDate、晚于 endDate 均不进入该区间，
 *       其中日期等于 startDate 的现金流显式拒绝（CASH_FLOW_OUTSIDE_RANGE），
 *       因为收盘口径下它已计入期初估值，纳入会重复计算。</li>
 * </ul>
 */
@Service
public class AttributionService {

    private final PortfolioStore store;
    private final ReturnCalculator returnCalculator;
    private final AttributionCalculator attributionCalculator;

    public AttributionService(PortfolioStore store,
                              ReturnCalculator returnCalculator,
                              AttributionCalculator attributionCalculator) {
        this.store = store;
        this.returnCalculator = returnCalculator;
        this.attributionCalculator = attributionCalculator;
    }

    public void recordValuation(String portfolioId, ValuationRequest request) {
        if (request == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "请求体为空");
        }
        Kind kind = parseKind(request.kind());
        LocalDate date = parseDate(request.date());
        BigDecimal totalValue = request.totalValue();
        if (totalValue == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "totalValue 不能为空");
        }
        if (totalValue.signum() < 0) {
            throw new ApiException(ErrorCode.NEGATIVE_ASSET,
                    "总资产市值不得为负: " + totalValue);
        }
        Map<String, BigDecimal> segments = request.segments();
        if (segments == null || segments.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "segments 不能为空");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> entry : segments.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "板块代码不能为空");
            }
            BigDecimal value = entry.getValue();
            if (value == null) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "板块市值不能为空: " + entry.getKey());
            }
            if (value.signum() < 0) {
                throw new ApiException(ErrorCode.NEGATIVE_HOLDING,
                        "板块持仓市值不得为负: " + entry.getKey() + "=" + value);
            }
            sum = sum.add(value);
        }
        if (sum.compareTo(totalValue) != 0) {
            throw new ApiException(ErrorCode.SEGMENT_SUM_MISMATCH,
                    "板块市值之和 " + sum + " 与总资产 " + totalValue + " 不一致");
        }
        ValuationSnapshot snapshot = new ValuationSnapshot(
                date, totalValue, Map.copyOf(segments));
        store.saveValuation(portfolioId, kind, snapshot);
    }

    public void recordCashFlow(String portfolioId, CashFlowRequest request) {
        if (request == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "请求体为空");
        }
        if (request.id() == null || request.id().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "现金流 id 不能为空");
        }
        LocalDate date = parseDate(request.date());
        BigDecimal amount = request.amount();
        if (amount == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "amount 不能为空");
        }
        if (amount.signum() == 0) {
            throw new ApiException(ErrorCode.ZERO_CASH_FLOW, "现金流金额不得为零");
        }
        if (request.segment() == null || request.segment().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "现金流必须指定板块 segment");
        }
        // 现金流允许先于估值乱序到达；板块是否存在于组合估值推迟到查询时校验，
        // 保证同一请求的拒绝原因不依赖录入先后顺序。
        store.saveCashFlow(portfolioId, new CashFlow(
                request.id(), date, amount, request.segment()));
    }

    public ReturnResult queryReturn(String portfolioId, String startDate, String endDate,
                                    ReturnKind returnKind) {
        LocalDate start = parseDate(startDate);
        LocalDate end = parseDate(endDate);
        validateRange(start, end);
        PortfolioData data = store.getData(portfolioId);
        TreeMap<LocalDate, ValuationSnapshot> valuations =
                rangeValuations(data.portfolioValuations(), start, end);
        requireEndpoints(valuations, start, end);
        List<CashFlow> cashFlows =
                rangeCashFlows(data.portfolioCashFlows(), start, end, valuations);
        requireKnownSegments(cashFlows, valuations);

        BigDecimal value;
        List<BigDecimal> subReturns;
        if (returnKind == ReturnKind.MONEY_WEIGHTED) {
            value = returnCalculator.moneyWeightedReturn(valuations, cashFlows);
            subReturns = List.of();
        } else {
            subReturns = returnCalculator.subPeriodReturns(valuations, cashFlows);
            value = returnCalculator.timeWeightedReturn(valuations, cashFlows);
        }
        return new ReturnResult(portfolioId, Kind.PORTFOLIO, start, end, returnKind,
                value, subReturns);
    }

    public AttributionResult queryAttribution(String portfolioId, String startDate,
                                              String endDate) {
        LocalDate start = parseDate(startDate);
        LocalDate end = parseDate(endDate);
        validateRange(start, end);
        PortfolioData data = store.getData(portfolioId);
        TreeMap<LocalDate, ValuationSnapshot> portfolio =
                rangeValuations(data.portfolioValuations(), start, end);
        TreeMap<LocalDate, ValuationSnapshot> benchmark =
                rangeValuations(data.benchmarkValuations(), start, end);
        requireEndpoints(portfolio, start, end);
        requireEndpoints(benchmark, start, end);
        List<CashFlow> cashFlows =
                rangeCashFlows(data.portfolioCashFlows(), start, end, portfolio);
        requireKnownSegments(cashFlows, portfolio);
        return attributionCalculator.attribute(portfolioId, start, end,
                portfolio, benchmark, cashFlows);
    }

    private Kind parseKind(String kind) {
        if (kind == null || kind.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "kind 不能为空");
        }
        try {
            return Kind.valueOf(kind.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "kind 只能是 PORTFOLIO 或 BENCHMARK");
        }
    }

    private LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "日期不能为空");
        }
        try {
            return LocalDate.parse(date.trim());
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "日期格式必须为 yyyy-MM-dd: " + date);
        }
    }

    private void validateRange(LocalDate start, LocalDate end) {
        if (start.isAfter(end)) {
            throw new ApiException(ErrorCode.INVALID_DATE_RANGE,
                    "起始日期不得晚于结束日期: " + start + " > " + end);
        }
    }

    private TreeMap<LocalDate, ValuationSnapshot> rangeValuations(
            Map<LocalDate, ValuationSnapshot> source, LocalDate start, LocalDate end) {
        TreeMap<LocalDate, ValuationSnapshot> result = new TreeMap<>();
        source.forEach((date, snapshot) -> {
            if (!date.isBefore(start) && !date.isAfter(end)) {
                result.put(date, snapshot);
            }
        });
        return result;
    }

    private void requireEndpoints(TreeMap<LocalDate, ValuationSnapshot> valuations,
                                  LocalDate start, LocalDate end) {
        if (!valuations.containsKey(start) || !valuations.containsKey(end)) {
            throw new ApiException(ErrorCode.MISSING_ENDPOINT_VALUATION,
                    "缺少区间端点估值: start=" + start + " end=" + end);
        }
    }

    private List<CashFlow> rangeCashFlows(List<CashFlow> cashFlows,
                                          LocalDate start, LocalDate end,
                                          Map<LocalDate, ValuationSnapshot> valuations) {
        for (CashFlow cashFlow : cashFlows) {
            if (cashFlow.date().isEqual(start)) {
                throw new ApiException(ErrorCode.CASH_FLOW_OUTSIDE_RANGE,
                        "现金流日期等于区间起始日 " + start
                                + "：收盘口径下已计入期初估值，纳入会重复计算（现金流 id="
                                + cashFlow.id() + ")");
            }
        }
        List<CashFlow> inRange = cashFlows.stream()
                .filter(cf -> cf.date().isAfter(start) && !cf.date().isAfter(end))
                .collect(Collectors.toList());
        for (CashFlow cashFlow : inRange) {
            if (!valuations.containsKey(cashFlow.date())) {
                throw new ApiException(ErrorCode.MISSING_INTERMEDIATE_VALUATION,
                        "现金流发生日 " + cashFlow.date() + " 缺少当日组合估值"
                                + "（现金流 id=" + cashFlow.id() + ")");
            }
        }
        return inRange;
    }

    private void requireKnownSegments(List<CashFlow> cashFlows,
                                      Map<LocalDate, ValuationSnapshot> valuations) {
        java.util.Set<String> knownSegments = new java.util.HashSet<>();
        valuations.values().forEach(snapshot ->
                knownSegments.addAll(snapshot.segments().keySet()));
        for (CashFlow cashFlow : cashFlows) {
            if (!knownSegments.contains(cashFlow.segment())) {
                throw new ApiException(ErrorCode.UNKNOWN_SEGMENT,
                        "现金流板块在组合估值中不存在: " + cashFlow.segment()
                                + "（现金流 id=" + cashFlow.id() + ")");
            }
        }
    }
}

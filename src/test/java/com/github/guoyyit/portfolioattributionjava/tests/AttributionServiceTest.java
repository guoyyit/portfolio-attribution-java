package com.github.guoyyit.portfolioattributionjava.tests;

import com.github.guoyyit.portfolioattributionjava.calc.AttributionCalculator;
import com.github.guoyyit.portfolioattributionjava.calc.ReturnCalculator;
import com.github.guoyyit.portfolioattributionjava.domain.ReturnKind;
import com.github.guoyyit.portfolioattributionjava.domain.ReturnResult;
import com.github.guoyyit.portfolioattributionjava.dto.CashFlowRequest;
import com.github.guoyyit.portfolioattributionjava.dto.ValuationRequest;
import com.github.guoyyit.portfolioattributionjava.service.AttributionService;
import com.github.guoyyit.portfolioattributionjava.store.EventLog;
import com.github.guoyyit.portfolioattributionjava.store.PortfolioStore;
import com.github.guoyyit.portfolioattributionjava.web.ApiException;
import com.github.guoyyit.portfolioattributionjava.web.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttributionServiceTest {

    private AttributionService service;
    private Path dataDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        dataDir = tempDir;
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        EventLog eventLog = new EventLog(tempDir.toString(), objectMapper);
        PortfolioStore store = new PortfolioStore(eventLog, objectMapper);
        store.restore();
        service = new AttributionService(store, new ReturnCalculator(),
                new AttributionCalculator(new ReturnCalculator()));
    }

    private void valuation(String kind, String date, String total, Map<String, String> segments) {
        var map = new java.util.HashMap<String, BigDecimal>();
        segments.forEach((key, value) -> map.put(key, new BigDecimal(value)));
        service.recordValuation("p1",
                new ValuationRequest(kind, date, new BigDecimal(total), map));
    }

    private void portfolio(String date, String total, Map<String, String> segments) {
        valuation("PORTFOLIO", date, total, segments);
    }

    private void benchmark(String date, String total, Map<String, String> segments) {
        valuation("BENCHMARK", date, total, segments);
    }

    @Test
    void outOfOrderIngestionProducesSameResult() {
        portfolio("2026-01-03", "110", Map.of("A", "110"));
        portfolio("2026-01-01", "100", Map.of("A", "100"));
        portfolio("2026-01-02", "105", Map.of("A", "105"));

        ReturnResult result = service.queryReturn(
                "p1", "2026-01-01", "2026-01-03", ReturnKind.TIME_WEIGHTED);
        // (105/100)*(110/105)-1 = 0.10
        assertEquals(0, new BigDecimal("0.1000000000").compareTo(result.value()));
        assertEquals(2, result.subReturns().size());
    }

    @Test
    void duplicateCashFlowIsIdempotentAndCorrectionOverwrites() {
        portfolio("2026-01-01", "100", Map.of("A", "100"));
        portfolio("2026-01-02", "130", Map.of("A", "130"));

        CashFlowRequest first = new CashFlowRequest("cf1", "2026-01-02",
                new BigDecimal("20"), "A");
        service.recordCashFlow("p1", first);
        // 重复提交同一请求：不产生第二条现金流
        service.recordCashFlow("p1", first);

        ReturnResult result = service.queryReturn(
                "p1", "2026-01-01", "2026-01-02", ReturnKind.TIME_WEIGHTED);
        // (130-20)/100-1 = 0.10
        assertEquals(0, new BigDecimal("0.1000000000").compareTo(result.value()));

        // 事后修正同一 id 现金流金额为 30
        service.recordCashFlow("p1", new CashFlowRequest("cf1", "2026-01-02",
                new BigDecimal("30"), "A"));
        ReturnResult corrected = service.queryReturn(
                "p1", "2026-01-01", "2026-01-02", ReturnKind.TIME_WEIGHTED);
        assertEquals(0, new BigDecimal("0.0000000000").compareTo(corrected.value()),
                "修正后 (130-30)/100-1=0，旧值不得残留");
    }

    @Test
    void correctedValuationUpdatesAffectedIntervalsOnly() {
        portfolio("2026-01-01", "100", Map.of("A", "100"));
        portfolio("2026-01-02", "105", Map.of("A", "105"));
        portfolio("2026-01-03", "110", Map.of("A", "110"));

        ReturnResult firstHalf = service.queryReturn(
                "p1", "2026-01-01", "2026-01-02", ReturnKind.TIME_WEIGHTED);
        assertEquals(0, new BigDecimal("0.0500000000").compareTo(firstHalf.value()));

        // 修正 1-3 的估值，不影响 [1-1,1-2]
        portfolio("2026-01-03", "120", Map.of("A", "120"));
        ReturnResult unaffected = service.queryReturn(
                "p1", "2026-01-01", "2026-01-02", ReturnKind.TIME_WEIGHTED);
        assertEquals(firstHalf.value(), unaffected.value(), "未受影响区间不得变化");

        ReturnResult affected = service.queryReturn(
                "p1", "2026-01-01", "2026-01-03", ReturnKind.TIME_WEIGHTED);
        assertEquals(0, new BigDecimal("0.2000000000").compareTo(affected.value()),
                "受影响区间必须同步更新");
    }

    @Test
    void arbitraryStartEndDatesAndSubIntervalCompounding() {
        portfolio("2026-01-01", "100", Map.of("A", "100"));
        portfolio("2026-01-02", "110", Map.of("A", "110"));
        portfolio("2026-01-03", "121", Map.of("A", "121"));

        ReturnResult r1 = service.queryReturn(
                "p1", "2026-01-01", "2026-01-02", ReturnKind.TIME_WEIGHTED);
        ReturnResult r2 = service.queryReturn(
                "p1", "2026-01-02", "2026-01-03", ReturnKind.TIME_WEIGHTED);
        ReturnResult whole = service.queryReturn(
                "p1", "2026-01-01", "2026-01-03", ReturnKind.TIME_WEIGHTED);

        BigDecimal compounded = BigDecimal.ONE.add(r1.value())
                .multiply(BigDecimal.ONE.add(r2.value())).subtract(BigDecimal.ONE);
        assertTrue(whole.value().subtract(compounded).abs().doubleValue() < 1e-9,
                "子区间复合必须等于整段: whole=" + whole.value() + " compounded=" + compounded);
    }

    @Test
    void rejectionErrorsAreStable() {
        assertRejects(ErrorCode.NEGATIVE_ASSET, () ->
                portfolio("2026-01-01", "-1", Map.of("A", "-1")));
        assertRejects(ErrorCode.NEGATIVE_HOLDING, () ->
                portfolio("2026-01-01", "100", Map.of("A", "-1", "B", "101")));
        assertRejects(ErrorCode.SEGMENT_SUM_MISMATCH, () ->
                portfolio("2026-01-01", "100", Map.of("A", "60", "B", "30")));

        portfolio("2026-01-01", "100", Map.of("A", "100"));
        portfolio("2026-01-02", "100", Map.of("A", "100"));

        assertRejects(ErrorCode.ZERO_CASH_FLOW, () ->
                service.recordCashFlow("p1", new CashFlowRequest(
                        "cf0", "2026-01-02", BigDecimal.ZERO, "A")));
        // 未知板块在录入时允许（乱序到达），查询区间包含该现金流时稳定拒绝
        service.recordCashFlow("p1", new CashFlowRequest(
                "cfx", "2026-01-02", new BigDecimal("5"), "ZZ"));
        assertRejects(ErrorCode.UNKNOWN_SEGMENT, () -> service.queryReturn(
                "p1", "2026-01-01", "2026-01-02", ReturnKind.TIME_WEIGHTED));
        assertRejects(ErrorCode.MISSING_ENDPOINT_VALUATION, () ->
                service.queryReturn("p1", "2026-01-01", "2026-01-04",
                        ReturnKind.TIME_WEIGHTED));
        assertRejects(ErrorCode.INVALID_DATE_RANGE, () ->
                service.queryReturn("p1", "2026-01-03", "2026-01-01",
                        ReturnKind.TIME_WEIGHTED));
    }

    @Test
    void cashFlowOnStartDateIsRejectedAsOutsideRange() {
        portfolio("2026-01-01", "100", Map.of("A", "100"));
        portfolio("2026-01-02", "120", Map.of("A", "120"));
        service.recordCashFlow("p1", new CashFlowRequest(
                "cf1", "2026-01-01", new BigDecimal("10"), "A"));

        ApiException ex = assertThrows(ApiException.class, () -> service.queryReturn(
                "p1", "2026-01-01", "2026-01-02", ReturnKind.TIME_WEIGHTED));
        assertEquals(ErrorCode.CASH_FLOW_OUTSIDE_RANGE, ex.getErrorCode());

        // 换一个起始日晚于现金流日期后，同一现金流成为历史数据、不再影响新区间
        portfolio("2026-01-03", "130", Map.of("A", "130"));
        ReturnResult result = service.queryReturn(
                "p1", "2026-01-02", "2026-01-03", ReturnKind.TIME_WEIGHTED);
        assertEquals(0, new BigDecimal("0.0833333333").compareTo(result.value()));
    }

    @Test
    void cashFlowWithoutSameDayValuationRejected() {
        portfolio("2026-01-01", "100", Map.of("A", "100"));
        portfolio("2026-01-03", "120", Map.of("A", "120"));
        service.recordCashFlow("p1", new CashFlowRequest(
                "cf1", "2026-01-02", new BigDecimal("10"), "A"));

        ApiException ex = assertThrows(ApiException.class, () -> service.queryReturn(
                "p1", "2026-01-01", "2026-01-03", ReturnKind.TIME_WEIGHTED));
        assertEquals(ErrorCode.MISSING_INTERMEDIATE_VALUATION, ex.getErrorCode());
    }

    private void assertRejects(ErrorCode expected, Runnable runnable) {
        ApiException ex = assertThrows(ApiException.class, runnable::run);
        assertEquals(expected, ex.getErrorCode());
    }
}

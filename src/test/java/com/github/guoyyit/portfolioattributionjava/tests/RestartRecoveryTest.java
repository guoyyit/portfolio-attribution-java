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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RestartRecoveryTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private AttributionService serviceOver(Path dir) {
        EventLog eventLog = new EventLog(dir.toString(), objectMapper);
        PortfolioStore store = new PortfolioStore(eventLog, objectMapper);
        store.restore();
        return new AttributionService(store, new ReturnCalculator(),
                new AttributionCalculator(new ReturnCalculator()));
    }

    @Test
    void dataAndResultsSurviveRestart(@TempDir Path dir) {
        AttributionService before = serviceOver(dir);
        before.recordValuation("p1", new ValuationRequest("PORTFOLIO",
                "2026-01-01", new BigDecimal("100"), Map.of("A", new BigDecimal("100"))));
        before.recordValuation("p1", new ValuationRequest("PORTFOLIO",
                "2026-01-02", new BigDecimal("120"), Map.of("A", new BigDecimal("120"))));
        before.recordCashFlow("p1", new CashFlowRequest(
                "cf1", "2026-01-02", new BigDecimal("10"), "A"));
        ReturnResult expected = before.queryReturn(
                "p1", "2026-01-01", "2026-01-02", ReturnKind.TIME_WEIGHTED);

        // 模拟进程重启：全新对象从事件日志重放
        AttributionService after = serviceOver(dir);
        ReturnResult recovered = after.queryReturn(
                "p1", "2026-01-01", "2026-01-02", ReturnKind.TIME_WEIGHTED);
        assertEquals(expected.value(), recovered.value());
        assertEquals(0, new BigDecimal("0.1000000000").compareTo(recovered.value()));
    }

    @Test
    void correctionEventIsReplayedOnRestart(@TempDir Path dir) {
        AttributionService service = serviceOver(dir);
        service.recordCashFlow("p1", cashFlow("cf1", "5"));
        service.recordValuation("p1", valuation("2026-01-01", "100"));
        service.recordValuation("p1", valuation("2026-01-02", "105"));
        // 修正同一 id 的现金流
        service.recordCashFlow("p1", cashFlow("cf1", "8"));

        AttributionService restarted = serviceOver(dir);
        ReturnResult result = restarted.queryReturn(
                "p1", "2026-01-01", "2026-01-02", ReturnKind.TIME_WEIGHTED);
        // 修正生效：(105-8)/100-1 = -0.03
        assertEquals(0, new BigDecimal("-0.0300000000").compareTo(result.value()));
    }

    @Test
    void tornTrailingWriteIsIgnoredNotPartiallyApplied(@TempDir Path dir) throws IOException {
        AttributionService service = serviceOver(dir);
        service.recordValuation("p1", valuation("2026-01-01", "100"));
        service.recordValuation("p1", valuation("2026-01-02", "105"));

        Path logFile = dir.resolve("event.log");
        long size = Files.size(logFile);
        // 模拟在写最后一条记录中途崩溃：追加 5 个垃圾字节
        try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "rw")) {
            raf.seek(size);
            raf.write(new byte[]{1, 2, 3, 4, 5});
        }

        AttributionService restarted = serviceOver(dir);
        ReturnResult result = restarted.queryReturn(
                "p1", "2026-01-01", "2026-01-02", ReturnKind.TIME_WEIGHTED);
        assertEquals(0, new BigDecimal("0.0500000000").compareTo(result.value()),
                "损坏的尾部写入不得部分生效");
    }

    private ValuationRequest valuation(String date, String value) {
        return new ValuationRequest("PORTFOLIO", date, new BigDecimal(value),
                Map.of("A", new BigDecimal(value)));
    }

    private CashFlowRequest cashFlow(String id, String amount) {
        return new CashFlowRequest(id, "2026-01-02", new BigDecimal(amount), "A");
    }
}

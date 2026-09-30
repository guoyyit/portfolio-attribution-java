package com.github.guoyyit.portfolioattributionjava.tests;

import com.github.guoyyit.portfolioattributionjava.calc.AttributionCalculator;
import com.github.guoyyit.portfolioattributionjava.calc.ReturnCalculator;
import com.github.guoyyit.portfolioattributionjava.domain.ReturnKind;
import com.github.guoyyit.portfolioattributionjava.dto.CashFlowRequest;
import com.github.guoyyit.portfolioattributionjava.dto.ValuationRequest;
import com.github.guoyyit.portfolioattributionjava.service.AttributionService;
import com.github.guoyyit.portfolioattributionjava.store.EventLog;
import com.github.guoyyit.portfolioattributionjava.store.PortfolioStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void queriesNeverObservePartialOrMixedState(@TempDir Path dir) throws Exception {
        EventLog eventLog = new EventLog(dir.toString(), objectMapper);
        PortfolioStore store = new PortfolioStore(eventLog, objectMapper);
        store.restore();
        AttributionService service = new AttributionService(store, new ReturnCalculator(),
                new AttributionCalculator(new ReturnCalculator()));

        int days = 40;
        // 先写端点与每天估值，保证查询随时能算出完整区间结果
        for (int i = 0; i <= days; i++) {
            LocalDate date = LocalDate.parse("2026-01-01").plusDays(i);
            service.recordValuation("p1", valuation(date, 100 + i));
        }

        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        // 2 个写线程：一个反复“修正”偶数日估值，一个反复重提同 id 现金流（幂等）
        for (int writer = 0; writer < 2; writer++) {
            final int writerId = writer;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int round = 0; round < 50; round++) {
                        LocalDate date = LocalDate.parse("2026-01-01")
                                .plusDays(1 + 2 * (round % (days / 2)));
                        int value = 100 + date.getDayOfYear() - 1 + writerId;
                        service.recordValuation("p1", valuation(date, value));
                        service.recordCashFlow("p1", new CashFlowRequest(
                                "cf-fixed", date.toString(), new BigDecimal("1"), "A"));
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }

        // 4 个查询线程：任何成功返回的结果都必须是内部自洽的完整快照
        for (int reader = 0; reader < 4; reader++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int round = 0; round < 200; round++) {
                        LocalDate s = LocalDate.parse("2026-01-01");
                        LocalDate e = LocalDate.parse("2026-01-01").plusDays(days);
                        var result = service.queryReturn(
                                "p1", s.toString(), e.toString(), ReturnKind.TIME_WEIGHTED);
                        // 自洽性 1：子区间数 = 估值跳数；自洽性 2：复合子区间=区间值
                        BigDecimal compounded = BigDecimal.ONE;
                        for (BigDecimal sub : result.subReturns()) {
                            compounded = compounded.multiply(BigDecimal.ONE.add(sub));
                        }
                        compounded = compounded.subtract(BigDecimal.ONE)
                                .setScale(10, java.math.RoundingMode.HALF_UP);
                        if (result.subReturns().size() != days
                                || result.value().subtract(compounded).abs().doubleValue() >= 1e-9) {
                            failures.add(new AssertionError(
                                    "读到不自洽结果: subs=" + result.subReturns().size()
                                            + " value=" + result.value()
                                            + " compounded=" + compounded));
                        }
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发任务未及时结束");
        assertTrue(failures.isEmpty(),
                "并发过程中出现不一致: " + failures.stream().findFirst().map(Throwable::toString)
                        .orElse("") + " 共 " + failures.size() + " 个");
    }

    private ValuationRequest valuation(LocalDate date, int total) {
        return new ValuationRequest("PORTFOLIO", date.toString(),
                BigDecimal.valueOf(total), Map.of("A", BigDecimal.valueOf(total)));
    }
}

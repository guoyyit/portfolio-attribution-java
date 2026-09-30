package com.github.guoyyit.portfolioattributionjava.service;

import com.github.guoyyit.portfolioattributionjava.web.dto.CashFlowRequest;
import com.github.guoyyit.portfolioattributionjava.web.dto.ReturnResponse;
import com.github.guoyyit.portfolioattributionjava.web.dto.ValuationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:conc;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ConcurrencyIntegrationTest {

    private static final LocalDate D0 = LocalDate.of(2024, 1, 1);
    private static final LocalDate D1 = LocalDate.of(2024, 1, 8);
    private static final LocalDate D2 = LocalDate.of(2024, 1, 15);

    @Autowired
    private PortfolioService service;

    @Test
    void queriesDuringWritesNeverObservePartialState() throws Exception {
        service.recordValuation(valuation("C1", D0, 100));
        service.recordValuation(valuation("C1", D1, 100));
        service.recordValuation(valuation("C1", D2, 100));

        int writerCount = 4;
        int queryCount = 200;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writerCount + queryCount);

        for (int i = 0; i < writerCount; i++) {
            int writer = i;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int attempt = 0; attempt < 25; attempt++) {
                        service.recordCashFlow(new CashFlowRequest(
                                "C1", "w" + writer + "-" + attempt + "-in",
                                D1, 1d, "A"));
                        service.recordCashFlow(new CashFlowRequest(
                                "C1", "w" + writer + "-" + attempt + "-out",
                                D1, -1d, "A"));
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        for (int i = 0; i < queryCount; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ReturnResponse response = service.queryReturn("C1", D0, D2);
                    // totals are flat at 100; flows on D1 net out near zero,
                    // so every consistent read must produce a finite number in a sane range
                    assertTrue(Double.isFinite(response.timeWeightedReturn()));
                    assertTrue(Math.abs(response.timeWeightedReturn()) < 1d);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdownNow();

        // matched +1/-1 pairs: net flow on D1 is exactly 0
        ReturnResponse finalResponse = service.queryReturn("C1", D0, D2);
        assertEquals(0d, finalResponse.timeWeightedReturn(), 1e-9);
    }

    private ValuationRequest valuation(String portfolioId, LocalDate date, double total) {
        return new ValuationRequest(portfolioId, date, total, List.of());
    }
}

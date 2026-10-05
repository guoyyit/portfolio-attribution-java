package com.github.guoyyit.portfolioattributionjava.service;

import com.github.guoyyit.portfolioattributionjava.error.RejectionException;
import com.github.guoyyit.portfolioattributionjava.web.dto.AttributionResponse;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发录入与查询交织时，每次查询只能看到某个完整提交点：
 * 写操作同时修改 valuation_snapshot（总市值）与 sector_valuation（先删后插），
 * 查询在单个 SNAPSHOT 只读事务内读取且持有进程内共享读锁。若读到跨表混合状态，
 * 要么出现 SECTOR_BREAKDOWN_MISSING/SECTOR_SET_MISMATCH 类拒绝，要么三项合计
 * 不等于超额收益，本测试即失败。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:xconc;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class CrossTableConcurrencyIntegrationTest {

    private static final LocalDate D0 = LocalDate.of(2024, 1, 1);
    private static final LocalDate D1 = LocalDate.of(2024, 1, 8);
    private static final LocalDate D2 = LocalDate.of(2024, 1, 15);

    @Autowired
    private PortfolioService service;

    @Test
    void attributionQueriesDuringCrossTableWritesAlwaysSeeCommittedPoints()
            throws Exception {
        putSnapshot("XP", D0, 100, 60, 40);
        putSnapshot("XP", D1, 110, 66, 44);
        putSnapshot("XP", D2, 121, 73, 48);
        putSnapshot("XB", D0, 100, 50, 50);
        putSnapshot("XB", D1, 105, 53, 52);
        putSnapshot("XB", D2, 110.25, 55.5, 54.75);

        int writerCount = 4;
        int readerCount = 6;
        int rounds = 60;
        ExecutorService pool = Executors.newFixedThreadPool(writerCount + readerCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writerCount + readerCount);
        AtomicInteger okReads = new AtomicInteger();

        for (int w = 0; w < writerCount; w++) {
            int writer = w;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int round = 0; round < rounds; round++) {
                        double bump = ((writer + round) % 3 - 1) * 2;
                        // 反复修正起点 D0 的“总市值 + 行业拆分”（两种权重形态交替提交）。
                        // D0 正是 DTO 中 portfolioWeight 所取的期初日期：读事务若跨
                        // 提交点混合两张表的数据，权重和立即偏离 1。
                        if ((writer + round) % 2 == 0) {
                            putSnapshot("XP", D0, 100 + bump, 60 + bump, 40);
                        } else {
                            putSnapshot("XP", D0, 100 + bump, 50 + bump, 50);
                        }
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        for (int r = 0; r < readerCount; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int round = 0; round < rounds; round++) {
                        AttributionResponse response =
                                service.queryAttribution("XP", "XB", D0, D2);
                        // 数据级不变量：每个提交点上行业期初权重之和必须为 1。
                        // 若读事务的多条 SELECT 跨越了写事务提交点，取到“新总市值 +
                        // 旧行业拆分”（或反之），该和必然偏离 1，且远大于舍入误差。
                        double weightSum = 0d;
                        double sectorEffectSum = 0d;
                        for (AttributionResponse.SectorAttributionDto sector
                                : response.sectors()) {
                            weightSum += sector.portfolioWeight();
                            sectorEffectSum += sector.allocationEffect()
                                    + sector.selectionEffect()
                                    + sector.interactionEffect();
                        }
                        assertEquals(1d, weightSum, 1e-6,
                                "mixed read: portfolio sector weights sum to "
                                        + weightSum + " (total value and sector "
                                        + "breakdown come from different commits)");
                        // 任何一次成功查询都必须满足舍入后的恒等容差
                        double sum = response.allocationEffect()
                                + response.selectionEffect()
                                + response.interactionEffect();
                        assertTrue(Math.abs(sum - response.excessReturn()) <= 2e-6,
                                "mixed read detected: effects " + sum
                                        + " vs excess " + response.excessReturn());
                        // 行业级效应之和与组合级三项合计一致（同一提交点的数据）
                        assertEquals(sum, sectorEffectSum, 2e-6);
                        okReads.incrementAndGet();
                    }
                } catch (RejectionException ex) {
                    throw new AssertionError(
                            "query rejected with " + ex.getErrorCode()
                                    + " during concurrent writes: " + ex.getMessage(),
                            ex);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(120, TimeUnit.SECONDS), "workers did not finish in time");
        pool.shutdownNow();
        assertTrue(okReads.get() > 0);
    }

    private void putSnapshot(String portfolioId, LocalDate date,
                             double total, double sectorA, double sectorB) {
        service.recordValuation(new ValuationRequest(
                portfolioId, date, total, List.of(
                        new ValuationRequest.SectorValueDto("A", sectorA),
                        new ValuationRequest.SectorValueDto("B", sectorB))));
    }
}

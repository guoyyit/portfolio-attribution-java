package com.github.guoyyit.portfolioattributionjava.service;

import com.github.guoyyit.portfolioattributionjava.web.dto.CashFlowRequest;
import com.github.guoyyit.portfolioattributionjava.web.dto.ReturnResponse;
import com.github.guoyyit.portfolioattributionjava.web.dto.ValuationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecoveryIntegrationTest {

    private static final LocalDate D0 = LocalDate.of(2024, 1, 1);
    private static final LocalDate D1 = LocalDate.of(2024, 1, 8);
    private static final LocalDate D2 = LocalDate.of(2024, 1, 15);

    @Test
    void dataAndResultsSurviveProcessRestart() throws Exception {
        Path dataDir = Files.createTempDirectory("portfolio-recovery");
        String jdbcUrl = "jdbc:h2:file:" + dataDir.resolve("recoverydb");

        try (ConfigurableApplicationContext first = start(jdbcUrl)) {
            PortfolioService service = first.getBean(PortfolioService.class);
            service.recordValuation(valuation("R1", D0, 100));
            service.recordValuation(valuation("R1", D1, 110));
            service.recordValuation(valuation("R1", D2, 115.5));
            service.recordCashFlow(flow("R1", "cf", D1, 10));
            assertEquals(0.05,
                    service.queryReturn("R1", D0, D2).timeWeightedReturn(), 1e-9);
        }

        try (ConfigurableApplicationContext second = start(jdbcUrl)) {
            PortfolioService service = second.getBean(PortfolioService.class);
            ReturnResponse response = service.queryReturn("R1", D0, D2);
            assertEquals(0.05, response.timeWeightedReturn(), 1e-9);
            // duplicate external id after restart stays idempotent
            service.recordCashFlow(flow("R1", "cf", D1, 10));
            assertEquals(0.05,
                    service.queryReturn("R1", D0, D2).timeWeightedReturn(), 1e-9);
        }

        deleteRecursively(dataDir);
    }

    @Test
    void correctionAfterRestartTakesEffect() throws Exception {
        Path dataDir = Files.createTempDirectory("portfolio-recovery-fix");
        String jdbcUrl = "jdbc:h2:file:" + dataDir.resolve("recoverydb");

        try (ConfigurableApplicationContext first = start(jdbcUrl)) {
            PortfolioService service = first.getBean(PortfolioService.class);
            service.recordValuation(valuation("R2", D0, 100));
            service.recordValuation(valuation("R2", D1, 110));
            service.recordValuation(valuation("R2", D2, 110));
            service.recordCashFlow(flow("R2", "cf", D1, 10));
        }

        try (ConfigurableApplicationContext second = start(jdbcUrl)) {
            PortfolioService service = second.getBean(PortfolioService.class);
            assertEquals(0d, service.queryReturn("R2", D0, D2).timeWeightedReturn(), 1e-9);
            service.recordCashFlow(flow("R2", "cf", D1, 5));
            assertEquals(0.05,
                    service.queryReturn("R2", D0, D2).timeWeightedReturn(), 1e-9);
        }

        deleteRecursively(dataDir);
    }

    private ConfigurableApplicationContext start(String jdbcUrl) {
        return new SpringApplicationBuilder(EmptyApp.class)
                .properties(
                        "spring.datasource.url=" + jdbcUrl,
                        "spring.jpa.hibernate.ddl-auto=update",
                        "spring.main.web-application-type=none",
                        "spring.task.scheduling.enabled=false")
                .run();
    }

    private void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted((left, right) -> right.compareTo(left))
                    .forEach(item -> {
                        try {
                            Files.deleteIfExists(item);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }

    private ValuationRequest valuation(String portfolioId, LocalDate date, double total) {
        return new ValuationRequest(portfolioId, date, total, List.of());
    }

    private CashFlowRequest flow(String portfolioId, String externalId,
                                 LocalDate date, double amount) {
        return new CashFlowRequest(portfolioId, externalId, date, amount, "A");
    }

    @org.springframework.boot.autoconfigure.SpringBootApplication(
            scanBasePackages = "com.github.guoyyit.portfolioattributionjava")
    static class EmptyApp {
    }
}

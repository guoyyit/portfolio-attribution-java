package com.github.guoyyit.portfolioattributionjava.repo;

import com.github.guoyyit.portfolioattributionjava.domain.CashFlow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CashFlowRepository extends JpaRepository<CashFlow, Long> {

    Optional<CashFlow> findByPortfolioIdAndExternalFlowId(
            String portfolioId, String externalFlowId);

    List<CashFlow> findByPortfolioIdAndFlowDateBetweenOrderByFlowDateAscExternalFlowIdAsc(
            String portfolioId, LocalDate from, LocalDate to);
}

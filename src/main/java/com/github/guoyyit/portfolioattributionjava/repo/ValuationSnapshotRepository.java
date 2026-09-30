package com.github.guoyyit.portfolioattributionjava.repo;

import com.github.guoyyit.portfolioattributionjava.domain.ValuationSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ValuationSnapshotRepository extends JpaRepository<ValuationSnapshot, Long> {

    Optional<ValuationSnapshot> findByPortfolioIdAndSnapshotDate(
            String portfolioId, LocalDate snapshotDate);

    List<ValuationSnapshot> findByPortfolioIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            String portfolioId, LocalDate from, LocalDate to);
}

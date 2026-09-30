package com.github.guoyyit.portfolioattributionjava.repo;

import com.github.guoyyit.portfolioattributionjava.domain.SectorValuation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface SectorValuationRepository extends JpaRepository<SectorValuation, Long> {

    List<SectorValuation> findByPortfolioIdAndSnapshotDateBetweenOrderBySnapshotDateAscSectorIdAsc(
            String portfolioId, LocalDate from, LocalDate to);

    List<SectorValuation> findByPortfolioIdAndSnapshotDateOrderBySectorIdAsc(
            String portfolioId, LocalDate snapshotDate);
}

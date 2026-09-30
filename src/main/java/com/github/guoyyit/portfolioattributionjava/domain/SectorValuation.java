package com.github.guoyyit.portfolioattributionjava.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;

@Entity
@Table(name = "sector_valuation", uniqueConstraints = {
        @UniqueConstraint(name = "uk_sector_val_pd_sector",
                columnNames = {"portfolioId", "snapshotDate", "sectorId"})
})
public class SectorValuation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String portfolioId;
    private LocalDate snapshotDate;
    private String sectorId;
    private double marketValue;

    protected SectorValuation() {
    }

    public SectorValuation(String portfolioId, LocalDate snapshotDate,
                           String sectorId, double marketValue) {
        this.portfolioId = portfolioId;
        this.snapshotDate = snapshotDate;
        this.sectorId = sectorId;
        this.marketValue = marketValue;
    }

    public Long getId() {
        return id;
    }

    public String getPortfolioId() {
        return portfolioId;
    }

    public LocalDate getSnapshotDate() {
        return snapshotDate;
    }

    public String getSectorId() {
        return sectorId;
    }

    public double getMarketValue() {
        return marketValue;
    }

    public void setMarketValue(double marketValue) {
        this.marketValue = marketValue;
    }
}

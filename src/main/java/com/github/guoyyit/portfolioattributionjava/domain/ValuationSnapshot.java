package com.github.guoyyit.portfolioattributionjava.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;

@Entity
@Table(name = "valuation_snapshot", uniqueConstraints = {
        @UniqueConstraint(name = "uk_snapshot_portfolio_date",
                columnNames = {"portfolioId", "snapshotDate"})
})
public class ValuationSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String portfolioId;
    private LocalDate snapshotDate;
    private double totalValue;

    protected ValuationSnapshot() {
    }

    public ValuationSnapshot(String portfolioId, LocalDate snapshotDate, double totalValue) {
        this.portfolioId = portfolioId;
        this.snapshotDate = snapshotDate;
        this.totalValue = totalValue;
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

    public double getTotalValue() {
        return totalValue;
    }

    public void setTotalValue(double totalValue) {
        this.totalValue = totalValue;
    }
}

package com.github.guoyyit.portfolioattributionjava.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;

@Entity
@Table(name = "cash_flow", uniqueConstraints = {
        @UniqueConstraint(name = "uk_cashflow_external",
                columnNames = {"portfolioId", "externalFlowId"})
})
public class CashFlow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String portfolioId;
    private String externalFlowId;
    private LocalDate flowDate;
    private double amount;
    private String sectorId;

    protected CashFlow() {
    }

    public CashFlow(String portfolioId, String externalFlowId, LocalDate flowDate,
                    double amount, String sectorId) {
        this.portfolioId = portfolioId;
        this.externalFlowId = externalFlowId;
        this.flowDate = flowDate;
        this.amount = amount;
        this.sectorId = sectorId;
    }

    public Long getId() {
        return id;
    }

    public String getPortfolioId() {
        return portfolioId;
    }

    public String getExternalFlowId() {
        return externalFlowId;
    }

    public LocalDate getFlowDate() {
        return flowDate;
    }

    public double getAmount() {
        return amount;
    }

    public String getSectorId() {
        return sectorId;
    }

    public void setFlowDate(LocalDate flowDate) {
        this.flowDate = flowDate;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public void setSectorId(String sectorId) {
        this.sectorId = sectorId;
    }
}

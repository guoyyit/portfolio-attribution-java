package com.github.guoyyit.portfolioattributionjava.calc;

import java.time.LocalDate;

public record CashFlowPoint(LocalDate date, double amount, String sectorId) {
}

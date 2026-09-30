package com.github.guoyyit.portfolioattributionjava.web;

import com.github.guoyyit.portfolioattributionjava.service.PortfolioService;
import com.github.guoyyit.portfolioattributionjava.web.dto.AttributionResponse;
import com.github.guoyyit.portfolioattributionjava.web.dto.CalculateReturnRequest;
import com.github.guoyyit.portfolioattributionjava.web.dto.CashFlowRequest;
import com.github.guoyyit.portfolioattributionjava.web.dto.ReturnResponse;
import com.github.guoyyit.portfolioattributionjava.web.dto.ValuationRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api")
public class PortfolioController {

    private final PortfolioService portfolioService;

    public PortfolioController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @PostMapping("/valuations")
    public org.springframework.http.ResponseEntity<Void> recordValuation(
            @RequestBody ValuationRequest request) {
        portfolioService.recordValuation(request);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @PostMapping("/cash-flows")
    public org.springframework.http.ResponseEntity<Void> recordCashFlow(
            @RequestBody CashFlowRequest request) {
        portfolioService.recordCashFlow(request);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @GetMapping("/returns")
    public ReturnResponse queryReturn(@RequestParam String portfolioId,
                                      @RequestParam LocalDate start,
                                      @RequestParam LocalDate end) {
        return portfolioService.queryReturn(portfolioId, start, end);
    }

    @GetMapping("/attribution")
    public AttributionResponse queryAttribution(@RequestParam String portfolioId,
                                                @RequestParam String benchmarkId,
                                                @RequestParam LocalDate start,
                                                @RequestParam LocalDate end) {
        return portfolioService.queryAttribution(portfolioId, benchmarkId, start, end);
    }

    @PostMapping("/calculate-returns")
    public ReturnResponse calculateReturn(@RequestBody CalculateReturnRequest request) {
        return portfolioService.calculateReturn(request);
    }
}

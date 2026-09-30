package com.github.guoyyit.portfolioattributionjava.web;

import com.github.guoyyit.portfolioattributionjava.domain.AttributionResult;
import com.github.guoyyit.portfolioattributionjava.domain.ReturnKind;
import com.github.guoyyit.portfolioattributionjava.domain.ReturnResult;
import com.github.guoyyit.portfolioattributionjava.dto.CashFlowRequest;
import com.github.guoyyit.portfolioattributionjava.dto.ValuationRequest;
import com.github.guoyyit.portfolioattributionjava.service.AttributionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PortfolioController {

    private final AttributionService attributionService;

    public PortfolioController(AttributionService attributionService) {
        this.attributionService = attributionService;
    }

    @PostMapping("/api/portfolios/{portfolioId}/valuations")
    public void recordValuation(@PathVariable String portfolioId,
                                @RequestBody ValuationRequest request) {
        attributionService.recordValuation(portfolioId, request);
    }

    @PostMapping("/api/portfolios/{portfolioId}/cash-flows")
    public void recordCashFlow(@PathVariable String portfolioId,
                               @RequestBody CashFlowRequest request) {
        attributionService.recordCashFlow(portfolioId, request);
    }

    @GetMapping("/api/portfolios/{portfolioId}/returns")
    public ReturnResult queryReturn(@PathVariable String portfolioId,
                                    @RequestParam String startDate,
                                    @RequestParam String endDate,
                                    @RequestParam(defaultValue = "TIME_WEIGHTED") ReturnKind returnKind) {
        return attributionService.queryReturn(portfolioId, startDate, endDate, returnKind);
    }

    @GetMapping("/api/portfolios/{portfolioId}/attribution")
    public AttributionResult queryAttribution(@PathVariable String portfolioId,
                                              @RequestParam String startDate,
                                              @RequestParam String endDate) {
        return attributionService.queryAttribution(portfolioId, startDate, endDate);
    }
}

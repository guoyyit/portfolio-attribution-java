package com.github.guoyyit.portfolioattributionjava.tests;

import com.github.guoyyit.portfolioattributionjava.domain.Kind;
import com.github.guoyyit.portfolioattributionjava.domain.ReturnKind;
import com.github.guoyyit.portfolioattributionjava.domain.ReturnResult;
import com.github.guoyyit.portfolioattributionjava.service.AttributionService;
import com.github.guoyyit.portfolioattributionjava.web.ApiException;
import com.github.guoyyit.portfolioattributionjava.web.ErrorCode;
import com.github.guoyyit.portfolioattributionjava.web.PortfolioController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PortfolioController.class)
class PortfolioControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AttributionService attributionService;

    @Test
    void recordsValuationAndReturns200() throws Exception {
        String body = """
                {"kind":"PORTFOLIO","date":"2026-01-01","totalValue":100,"segments":{"A":100}}
                """;
        mockMvc.perform(post("/api/portfolios/p1/valuations")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void businessRejectionCarriesStableErrorCode() throws Exception {
        when(attributionService.queryReturn(eq("p1"), eq("2026-01-01"), eq("2026-01-09"),
                any(ReturnKind.class)))
                .thenThrow(new ApiException(ErrorCode.MISSING_ENDPOINT_VALUATION,
                        "缺少区间端点估值: start=2026-01-01 end=2026-01-09"));

        mockMvc.perform(get("/api/portfolios/p1/returns")
                        .param("startDate", "2026-01-01")
                        .param("endDate", "2026-01-09"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("MISSING_ENDPOINT_VALUATION"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void invalidEnumIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/portfolios/p1/returns")
                        .param("startDate", "2026-01-01")
                        .param("endDate", "2026-01-09")
                        .param("returnKind", "NOT_A_KIND"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    void returnsResponseIncludesSubReturns() throws Exception {
        when(attributionService.queryReturn(eq("p1"), eq("2026-01-01"), eq("2026-01-03"),
                eq(ReturnKind.TIME_WEIGHTED)))
                .thenReturn(new ReturnResult("p1", Kind.PORTFOLIO,
                        LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-03"),
                        ReturnKind.TIME_WEIGHTED, new BigDecimal("0.1025"),
                        List.of(new BigDecimal("0.05"), new BigDecimal("0.05"))));

        mockMvc.perform(get("/api/portfolios/p1/returns")
                        .param("startDate", "2026-01-01")
                        .param("endDate", "2026-01-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(0.1025))
                .andExpect(jsonPath("$.subReturns.length()").value(2));
    }
}

package com.github.guoyyit.portfolioattributionjava.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动真实 Web 容器，通过对外 HTTP 接口完成端到端复核：
 * 录入（快照 + 现金流）→ 收益查询 → 修正 → 再查询 → 现金流时点边界拒绝 →
 * 归因查询（三项恒等于超额收益）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:e2e;DB_CLOSE_DELAY=-1",
                "spring.jpa.hibernate.ddl-auto=create-drop"
        })
class EndToEndHttpIntegrationTest {

    private static final String P = "E2E-P";
    private static final String B = "E2E-B";
    private static final LocalDate D0 = LocalDate.of(2024, 3, 1);
    private static final LocalDate D1 = LocalDate.of(2024, 3, 8);
    private static final LocalDate DX = LocalDate.of(2024, 3, 11);
    private static final LocalDate D2 = LocalDate.of(2024, 3, 15);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void endToEndOverHttp() throws Exception {
        // 1) 录入组合估值 D0/D1/D2（全程含行业拆分）与 D1 现金流
        valuation(P, D0, 100, sectors(60, 40));
        valuation(P, D1, 110, sectors(66, 44));
        valuation(P, D2, 121, sectors(73, 48));
        cashFlow(P, "e2e-cf", D1, 10, "A");

        // 2) 录入基准（无外部现金流）
        valuation(B, D0, 100, sectors(50, 50));
        valuation(B, D1, 105, sectors(53, 52));
        valuation(B, D2, 110.25, sectors(55.5, 54.75));

        // 3) 收益查询：r1 = (110-100-10)/100 = 0；r2 = 121/110 - 1 = 10%
        JsonNode returns = getReturns(P, D0, D2);
        assertEquals(0.10, returns.get("timeWeightedReturn").asDouble(), 1e-9);
        // MWR: gain = 121-100-10 = 11, w = 7/14, denom = 105
        assertEquals(11d / 105d, returns.get("moneyWeightedReturn").asDouble(), 1e-6);

        // 4) 修正现金流 10 -> 20，并把 D2 快照修正为 130
        cashFlow(P, "e2e-cf", D1, 20, "A");
        valuation(P, D2, 130, sectors(78, 52));
        JsonNode afterCorrection = getReturns(P, D0, D2);
        // r1 = (110-100-20)/100 = -0.10；r2 = 130/110 - 1
        double expected = (1 - 0.10) * (130d / 110d) - 1;
        assertEquals(expected,
                afterCorrection.get("timeWeightedReturn").asDouble(), 1e-6);

        // 5) 归因查询：三项合计（舍入后）必须等于超额收益（容差 2e-6）
        JsonNode attribution = getAttribution(P, B, D0, D2);
        double effectSum = attribution.get("allocationEffect").asDouble()
                + attribution.get("selectionEffect").asDouble()
                + attribution.get("interactionEffect").asDouble();
        assertEquals(attribution.get("excessReturn").asDouble(), effectSum, 2e-6);
        assertEquals(2, attribution.get("sectors").size());
        double weightSum = 0d;
        for (JsonNode sector : attribution.get("sectors")) {
            weightSum += sector.get("portfolioWeight").asDouble();
        }
        assertEquals(1d, weightSum, 1e-6);

        // 6) 现金流落在无快照日期 DX：两种口径一致拒绝，错误码稳定可区分
        cashFlow(P, "e2e-orphan", DX, 10, "A");
        ResponseEntity<String> rejected = rest.getForEntity(
                returnsUrl(P, D0, D2), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());
        JsonNode errorBody = json.readTree(rejected.getBody());
        assertEquals("CASH_FLOW_WITHOUT_VALUATION", errorBody.get("code").asText());
        assertTrue(errorBody.get("message").asText().contains(DX.toString()));

        // 7) 补录 DX 当日快照后同一区间恢复可查
        valuation(P, DX, 120, sectors(72, 48));
        JsonNode recovered = getReturns(P, D0, D2);
        assertTrue(Double.isFinite(recovered.get("timeWeightedReturn").asDouble()));
    }

    private List<Sector> sectors(double a, double b) {
        return List.of(new Sector("A", a), new Sector("B", b));
    }

    private String base() {
        return "http://localhost:" + port + "/api";
    }

    private void valuation(String portfolioId, LocalDate date, double total,
                           List<Sector> sectorList) throws Exception {
        String body = "{\"portfolioId\":\"" + portfolioId + "\",\"date\":\"" + date
                + "\",\"totalValue\":" + total
                + ",\"sectors\":" + json.writeValueAsString(sectorList) + "}";
        post("/valuations", body);
    }

    private void cashFlow(String portfolioId, String flowId, LocalDate date,
                          double amount, String sectorId) {
        String body = "{\"portfolioId\":\"" + portfolioId
                + "\",\"externalFlowId\":\"" + flowId + "\",\"date\":\"" + date
                + "\",\"amount\":" + amount + ",\"sectorId\":\"" + sectorId + "\"}";
        post("/cash-flows", body);
    }

    private void post(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.postForEntity(
                base() + path, new HttpEntity<>(body, headers), String.class);
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode(),
                "POST " + path + " -> " + response.getBody());
    }

    private JsonNode getReturns(String portfolioId, LocalDate start, LocalDate end)
            throws Exception {
        ResponseEntity<String> response = rest.getForEntity(
                returnsUrl(portfolioId, start, end), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        return json.readTree(response.getBody());
    }

    private String returnsUrl(String portfolioId, LocalDate start, LocalDate end) {
        return base() + "/returns?portfolioId=" + portfolioId
                + "&start=" + start + "&end=" + end;
    }

    private JsonNode getAttribution(String portfolioId, String benchmarkId,
                                    LocalDate start, LocalDate end) throws Exception {
        ResponseEntity<String> response = rest.getForEntity(
                base() + "/attribution?portfolioId=" + portfolioId
                        + "&benchmarkId=" + benchmarkId
                        + "&start=" + start + "&end=" + end,
                String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        return json.readTree(response.getBody());
    }

    private record Sector(String sectorId, double marketValue) {
    }
}

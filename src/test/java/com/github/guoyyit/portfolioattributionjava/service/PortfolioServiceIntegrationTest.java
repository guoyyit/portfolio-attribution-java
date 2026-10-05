package com.github.guoyyit.portfolioattributionjava.service;

import com.github.guoyyit.portfolioattributionjava.error.ErrorCode;
import com.github.guoyyit.portfolioattributionjava.error.RejectionException;
import com.github.guoyyit.portfolioattributionjava.web.dto.CashFlowRequest;
import com.github.guoyyit.portfolioattributionjava.web.dto.AttributionResponse;
import com.github.guoyyit.portfolioattributionjava.web.dto.CalculateReturnRequest;
import com.github.guoyyit.portfolioattributionjava.web.dto.ReturnResponse;
import com.github.guoyyit.portfolioattributionjava.web.dto.ValuationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:it;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class PortfolioServiceIntegrationTest {

    private static final LocalDate D0 = LocalDate.of(2024, 1, 1);
    private static final LocalDate D1 = LocalDate.of(2024, 1, 8);
    private static final LocalDate D2 = LocalDate.of(2024, 1, 15);

    @Autowired
    private PortfolioService service;

    @Test
    void outOfOrderEntryYieldsSameResultAsOrderedEntry() {
        service.recordValuation(valuation("P1", D2, 115.5));
        service.recordCashFlow(flow("P1", "f1", D1, 10));
        service.recordValuation(valuation("P1", D0, 100));
        service.recordValuation(valuation("P1", D1, 110));

        ReturnResponse response = service.queryReturn("P1", D0, D2);

        // r1 = 0 ; r2 = 115.5/110 - 1 = 0.05
        assertEquals(0.05, response.timeWeightedReturn(), 1e-9);
    }

    @Test
    void duplicateSubmissionsAreIdempotent() {
        service.recordCashFlow(flow("P2", "dup", D1, 10));
        service.recordCashFlow(flow("P2", "dup", D1, 10));
        service.recordValuation(valuation("P2", D0, 100));
        service.recordValuation(valuation("P2", D1, 110));
        service.recordValuation(valuation("P2", D2, 121));

        ReturnResponse response = service.queryReturn("P2", D0, D2);

        // flow counts once: r1 = 0, r2 = 10%
        assertEquals(0.10, response.timeWeightedReturn(), 1e-9);
    }

    @Test
    void correctingACashFlowUpdatesAffectedIntervalOnly() {
        service.recordValuation(valuation("P3", D0, 100));
        service.recordValuation(valuation("P3", D1, 110));
        service.recordValuation(valuation("P3", D2, 110));
        service.recordCashFlow(flow("P3", "rev", D1, 10));

        assertEquals(0d, service.queryReturn("P3", D0, D1).timeWeightedReturn(), 1e-9);
        assertEquals(0d, service.queryReturn("P3", D0, D2).timeWeightedReturn(), 1e-9);

        service.recordCashFlow(flow("P3", "rev", D1, 5));

        // full interval changes: r1 = 5%, r2 = 0
        assertEquals(0.05, service.queryReturn("P3", D0, D2).timeWeightedReturn(), 1e-9);
        // later unaffected interval stays zero
        assertEquals(0d, service.queryReturn("P3", D1, D2).timeWeightedReturn(), 1e-9);
    }

    @Test
    void correctingASnapshotReplacesSectorBreakdownAtomically() {
        service.recordValuation(new ValuationRequest("P4", D0, 100d, List.of(
                new ValuationRequest.SectorValueDto("A", 60d),
                new ValuationRequest.SectorValueDto("B", 40d))));
        service.recordValuation(new ValuationRequest("P4", D0, 100d, List.of(
                new ValuationRequest.SectorValueDto("A", 70d),
                new ValuationRequest.SectorValueDto("B", 30d))));

        service.recordValuation(new ValuationRequest("P4", D1, 110d, List.of(
                new ValuationRequest.SectorValueDto("A", 77d),
                new ValuationRequest.SectorValueDto("B", 33d))));

        ReturnResponse response = service.queryReturn("P4", D0, D1);
        assertEquals(0.10, response.timeWeightedReturn(), 1e-9);
    }

    @Test
    void rejectsNegativeValuesZeroFlowAndMissingEndpointsWithStableCodes() {
        assertRejects(ErrorCode.NEGATIVE_TOTAL_VALUE,
                () -> service.recordValuation(valuation("P5", D0, -1)));
        assertRejects(ErrorCode.NEGATIVE_SECTOR_VALUE,
                () -> service.recordValuation(new ValuationRequest("P5", D0, 100d, List.of(
                        new ValuationRequest.SectorValueDto("A", 101d),
                        new ValuationRequest.SectorValueDto("B", -1d)))));
        assertRejects(ErrorCode.ZERO_CASH_FLOW,
                () -> service.recordCashFlow(flow("P5", "z", D1, 0)));

        service.recordValuation(valuation("P5", D0, 100));
        assertRejects(ErrorCode.MISSING_ENDPOINT_VALUATION,
                () -> service.queryReturn("P5", D0, D1));
        assertRejects(ErrorCode.INVALID_DATE_RANGE,
                () -> service.queryReturn("P5", D1, D0));
    }

    @Test
    void attributionEffectsSumToExcessReturnForPersistedData() {
        recordBenchmark();
        service.recordValuation(new ValuationRequest("P6", D0, 100d, List.of(
                new ValuationRequest.SectorValueDto("A", 70d),
                new ValuationRequest.SectorValueDto("B", 30d))));
        service.recordValuation(new ValuationRequest("P6", D1, 104d, List.of(
                new ValuationRequest.SectorValueDto("A", 77d),
                new ValuationRequest.SectorValueDto("B", 27d))));

        AttributionResponse response = service.queryAttribution("P6", "B6", D0, D1);

        assertEquals(0.04, response.portfolioReturn(), 1e-9);
        assertEquals(0.05, response.benchmarkReturn(), 1e-9);
        assertEquals(-0.01, response.excessReturn(), 1e-9);
        assertEquals(-0.01,
                response.allocationEffect() + response.selectionEffect()
                        + response.interactionEffect(), 1e-9);
    }

    @Test
    void adhocCalculationRejectsOutOfRangeAndZeroCashFlowsStably() {
        List<CalculateReturnRequest.ValuationPointDto> valuations = List.of(
                new CalculateReturnRequest.ValuationPointDto(D0, 100d),
                new CalculateReturnRequest.ValuationPointDto(D2, 110d));

        CalculateReturnRequest outOfRange = new CalculateReturnRequest(
                valuations, List.of(new CalculateReturnRequest.CashFlowPointDto(
                        D2.plusDays(1), 5d)));
        RejectionException outException = assertThrows(RejectionException.class,
                () -> service.calculateReturn(outOfRange));
        assertEquals(ErrorCode.CASH_FLOW_OUT_OF_RANGE, outException.getErrorCode());

        CalculateReturnRequest atStart = new CalculateReturnRequest(
                valuations, List.of(new CalculateReturnRequest.CashFlowPointDto(D0, 5d)));
        RejectionException startException = assertThrows(RejectionException.class,
                () -> service.calculateReturn(atStart));
        assertEquals(ErrorCode.CASH_FLOW_OUT_OF_RANGE, startException.getErrorCode());

        CalculateReturnRequest zero = new CalculateReturnRequest(
                valuations, List.of(new CalculateReturnRequest.CashFlowPointDto(D1, 0d)));
        RejectionException zeroException = assertThrows(RejectionException.class,
                () -> service.calculateReturn(zero));
        assertEquals(ErrorCode.ZERO_CASH_FLOW, zeroException.getErrorCode());
    }

    @Test
    void cashFlowOnDateWithoutSnapshotIsCountedByBothReturnMeasures() {
        service.recordValuation(valuation("P7", D0, 100));
        service.recordValuation(valuation("P7", D2, 110));
        service.recordCashFlow(flow("P7", "mid", D1, 5)); // no snapshot at D1

        ReturnResponse response = service.queryReturn("P7", D0, D2);

        // TWR: flow joins sub-interval (D0, D2]; MWR: weighted by actual date (7/14)
        // (outputs are rounded to 6 decimal places)
        assertEquals(0.05, response.timeWeightedReturn(), 1e-9);
        assertEquals(5d / 102.5d, response.moneyWeightedReturn(), 1e-6);
    }

    @Test
    void adhocCalculationCountsFlowOnDateWithoutValuation() {
        CalculateReturnRequest request = new CalculateReturnRequest(
                List.of(new CalculateReturnRequest.ValuationPointDto(D0, 100d),
                        new CalculateReturnRequest.ValuationPointDto(D2, 110d)),
                List.of(new CalculateReturnRequest.CashFlowPointDto(D1, 5d)));

        ReturnResponse response = service.calculateReturn(request);

        // same boundary rule as persisted queries: both measures count the flow
        assertEquals(0.05, response.timeWeightedReturn(), 1e-9);
        assertEquals(5d / 102.5d, response.moneyWeightedReturn(), 1e-6);
    }

    @Test
    void twrFromQueryCompoundsAcrossAdjacentSubIntervals() {
        LocalDate dMid = LocalDate.of(2024, 1, 10); // no snapshot here
        service.recordValuation(valuation("P8", D0, 100));
        service.recordValuation(valuation("P8", D1, 108));
        service.recordValuation(valuation("P8", D2, 120));
        service.recordCashFlow(flow("P8", "mid", dMid, 6));

        double whole = service.queryReturn("P8", D0, D2).timeWeightedReturn();
        double first = service.queryReturn("P8", D0, D1).timeWeightedReturn();
        double second = service.queryReturn("P8", D1, D2).timeWeightedReturn();

        // documented rounding tolerance for compounded rounded outputs is 2e-6
        assertEquals(whole, (1 + first) * (1 + second) - 1, 2e-6);
        // and the whole-interval value is the exact compounded sub-interval result
        assertEquals(1.08 * (1 + 6d / 108d) - 1, whole, 1e-6);
    }

    @Test
    void attributionWithoutAnySectorBreakdownSatisfiesIdentity() {
        service.recordValuation(valuation("P9", D0, 100));
        service.recordValuation(valuation("P9", D1, 110));
        service.recordValuation(valuation("P9", D2, 121));
        service.recordValuation(valuation("B9", D0, 100));
        service.recordValuation(valuation("B9", D1, 105));
        service.recordValuation(valuation("B9", D2, 108));

        AttributionResponse response = service.queryAttribution("P9", "B9", D0, D2);

        assertEquals(0d, response.allocationEffect(), 1e-9);
        assertEquals(response.excessReturn(), response.selectionEffect(), 1e-9);
        assertEquals(0d, response.interactionEffect(), 1e-9);
        assertTrue(response.sectors().isEmpty());
        // identity on rounded outputs within the documented 2e-6 tolerance
        assertEquals(response.excessReturn(),
                response.allocationEffect() + response.selectionEffect()
                        + response.interactionEffect(), 2e-6);
        // excess is non-zero: TWR_P = 0.21, TWR_B = 0.08
        assertEquals(0.13, response.excessReturn(), 1e-9);
    }

    @Test
    void attributionWithOneSidedSectorBreakdownDegradesAndKeepsIdentity() {
        service.recordValuation(new ValuationRequest("P10", D0, 100d, List.of(
                new ValuationRequest.SectorValueDto("A", 60d),
                new ValuationRequest.SectorValueDto("B", 40d))));
        service.recordValuation(new ValuationRequest("P10", D1, 110d, List.of(
                new ValuationRequest.SectorValueDto("A", 69d),
                new ValuationRequest.SectorValueDto("B", 41d))));
        service.recordValuation(valuation("B10", D0, 100));
        service.recordValuation(valuation("B10", D1, 105));

        AttributionResponse response = service.queryAttribution("P10", "B10", D0, D1);

        assertEquals(0.10, response.portfolioReturn(), 1e-9);
        assertEquals(0.05, response.benchmarkReturn(), 1e-9);
        assertEquals(0.05, response.excessReturn(), 1e-9);
        assertEquals(0d, response.allocationEffect(), 1e-9);
        assertEquals(response.excessReturn(), response.selectionEffect(), 1e-9);
        assertEquals(0d, response.interactionEffect(), 1e-9);
        assertTrue(response.sectors().isEmpty());
        assertEquals(response.excessReturn(),
                response.allocationEffect() + response.selectionEffect()
                        + response.interactionEffect(), 2e-6);
    }

    @Test
    void correctingValuationAffectsOnlyIntervalsThatUseIt() {
        LocalDate d3 = LocalDate.of(2024, 1, 22);
        service.recordValuation(valuation("P11", D0, 100));
        service.recordValuation(valuation("P11", D1, 110));
        service.recordValuation(valuation("P11", D2, 121));
        service.recordValuation(valuation("P11", d3, 133.1));

        assertEquals(0.10, service.queryReturn("P11", D2, d3).timeWeightedReturn(), 1e-9);
        double before = service.queryReturn("P11", D0, D2).timeWeightedReturn();
        assertEquals(0.21, before, 1e-9);

        // correct the D1 snapshot: intervals containing D1 change, others do not
        service.recordValuation(valuation("P11", D1, 108));

        assertEquals(0.10, service.queryReturn("P11", D2, d3).timeWeightedReturn(), 1e-9);
        double after = service.queryReturn("P11", D0, D2).timeWeightedReturn();
        assertEquals(1.08 * (121d / 108d) - 1, after, 1e-9);

        // the corrected interval still compounds exactly from its adjacent parts
        double whole = service.queryReturn("P11", D0, d3).timeWeightedReturn();
        double first = service.queryReturn("P11", D0, D1).timeWeightedReturn();
        double second = service.queryReturn("P11", D1, d3).timeWeightedReturn();
        assertEquals(whole, (1 + first) * (1 + second) - 1, 2e-6);
    }

    private void recordBenchmark() {
        service.recordValuation(new ValuationRequest("B6", D0, 100d, List.of(
                new ValuationRequest.SectorValueDto("A", 50d),
                new ValuationRequest.SectorValueDto("B", 50d))));
        service.recordValuation(new ValuationRequest("B6", D1, 105d, List.of(
                new ValuationRequest.SectorValueDto("A", 55d),
                new ValuationRequest.SectorValueDto("B", 50d))));
    }

    private void assertRejects(ErrorCode code, Runnable runnable) {
        RejectionException ex = assertThrows(RejectionException.class, runnable::run);
        assertEquals(code, ex.getErrorCode());
    }

    private ValuationRequest valuation(String portfolioId, LocalDate date, double total) {
        return new ValuationRequest(portfolioId, date, total, List.of());
    }

    private CashFlowRequest flow(String portfolioId, String externalId,
                                 LocalDate date, double amount) {
        return new CashFlowRequest(portfolioId, externalId, date, amount, "A");
    }
}

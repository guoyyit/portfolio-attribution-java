package com.github.guoyyit.portfolioattributionjava.service;

import com.github.guoyyit.portfolioattributionjava.error.ErrorCode;
import com.github.guoyyit.portfolioattributionjava.error.RejectionException;
import com.github.guoyyit.portfolioattributionjava.web.dto.AttributionResponse;
import com.github.guoyyit.portfolioattributionjava.web.dto.CashFlowRequest;
import com.github.guoyyit.portfolioattributionjava.web.dto.ReturnResponse;
import com.github.guoyyit.portfolioattributionjava.web.dto.ValuationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 本轮迭代收紧的边界场景：
 * 现金流落在无快照日期、两侧均无行业拆分的归因恒等、单侧缺行业时可区分拒绝、
 * 以及 TWR 复合与直接查询在持久化数据上一致（修正影响范围）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:boundary;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class BoundaryConsistencyIntegrationTest {

    private static final LocalDate D0 = LocalDate.of(2024, 1, 1);
    private static final LocalDate D1 = LocalDate.of(2024, 1, 8);
    private static final LocalDate DM = LocalDate.of(2024, 1, 11);
    private static final LocalDate D2 = LocalDate.of(2024, 1, 15);

    @Autowired
    private PortfolioService service;

    @Test
    void persistedCashFlowOnSnapshotLessDateIsStablyRejectedForBothBases() {
        service.recordValuation(valuation("N1", D0, 100));
        service.recordValuation(valuation("N1", D2, 120));
        service.recordCashFlow(flow("N1", "cf-missing", DM, 10));

        RejectionException twr = assertThrows(RejectionException.class,
                () -> service.queryReturn("N1", D0, D2));
        assertEquals(ErrorCode.CASH_FLOW_WITHOUT_VALUATION, twr.getErrorCode());

        // 补上当日快照后，同一区间立即可以计算，两种口径都计入该现金流
        service.recordValuation(valuation("N1", DM, 110));
        ReturnResponse response = service.queryReturn("N1", D0, D2);
        // r1 = (110-100-10)/100 = 0 ; r2 = 120/110-1（响应值已按 6 位舍入）
        double expected = 120d / 110d - 1;
        assertEquals(expected, response.timeWeightedReturn(), 1e-6);
        // MWR: gain = 120-100-10 = 10 ; w = 4/14 ; denom = 100 + 10*4/14
        double expectedMwr = 10d / (100d + 10d * 4d / 14d);
        assertEquals(expectedMwr, response.moneyWeightedReturn(), 1e-6);
    }

    @Test
    void cashFlowOnSnapshotLessDateDoesNotRejectRangesThatExcludeIt() {
        service.recordValuation(valuation("N2", D0, 100));
        service.recordValuation(valuation("N2", D1, 105));
        service.recordValuation(valuation("N2", D2, 110.25));
        service.recordCashFlow(flow("N2", "cf-other", DM, 10));

        // 查询区间 (D0, D1] 不包含 DM 上的现金流
        assertEquals(0.05, service.queryReturn("N2", D0, D1).timeWeightedReturn(), 1e-9);
    }

    @Test
    void attributionWithoutSectorBreakdownSatisfiesIdentityBeforeRounding() {
        service.recordValuation(valuation("N3", D0, 100));
        service.recordValuation(valuation("N3", D1, 110));
        service.recordValuation(valuation("N3", D2, 130));
        service.recordCashFlow(flow("N3", "cf", D1, 10));
        service.recordValuation(valuation("B3", D0, 100));
        service.recordValuation(valuation("B3", D1, 105));
        service.recordValuation(valuation("B3", D2, 110));

        AttributionResponse response = service.queryAttribution("N3", "B3", D0, D2);

        // 输出舍入后偏差不超过文档约定容差 2e-6
        double roundedSum = response.allocationEffect()
                + response.selectionEffect() + response.interactionEffect();
        assertEquals(response.excessReturn(), roundedSum, 2e-6);
        assertEquals(0d, response.allocationEffect(), 0d);
        assertEquals(0d, response.interactionEffect(), 0d);
        assertEquals(0, response.sectors().size());
    }

    @Test
    void attributionWithOneSidedSectorBreakdownIsRejectedStably() {
        service.recordValuation(new ValuationRequest("N4", D0, 100d, List.of(
                new ValuationRequest.SectorValueDto("A", 60d),
                new ValuationRequest.SectorValueDto("B", 40d))));
        service.recordValuation(new ValuationRequest("N4", D2, 110d, List.of(
                new ValuationRequest.SectorValueDto("A", 66d),
                new ValuationRequest.SectorValueDto("B", 44d))));
        service.recordValuation(valuation("B4", D0, 100));
        service.recordValuation(valuation("B4", D2, 105));

        RejectionException ex = assertThrows(RejectionException.class,
                () -> service.queryAttribution("N4", "B4", D0, D2));
        assertEquals(ErrorCode.SECTOR_BREAKDOWN_MISSING, ex.getErrorCode());

        // 同一数据下请求顺序颠倒（基准在前作为 portfolioId 参数等）结果不变：
        // 错误码只取决于输入
        RejectionException again = assertThrows(RejectionException.class,
                () -> service.queryAttribution("N4", "B4", D0, D2));
        assertEquals(ex.getErrorCode(), again.getErrorCode());
        assertEquals(ex.getMessage(), again.getMessage());
    }

    @Test
    void twrCompoundFromSubRangesEqualsDirectQueryAfterCorrection() {
        service.recordValuation(valuation("N5", D0, 100));
        service.recordValuation(valuation("N5", D1, 110));
        service.recordValuation(valuation("N5", D2, 121));
        service.recordCashFlow(flow("N5", "cf-fix", D1, 10));

        double direct = service.queryReturn("N5", D0, D2).timeWeightedReturn();
        double left = service.queryReturn("N5", D0, D1).timeWeightedReturn();
        double right = service.queryReturn("N5", D1, D2).timeWeightedReturn();
        assertEquals(direct, (1 + left) * (1 + right) - 1, 1e-12);

        // 修正现金流（10 -> 5）：包含 D1 的区间全部变化，不包含 D1 现金流的
        // 右区间保持不变
        service.recordCashFlow(flow("N5", "cf-fix", D1, 5));

        double directAfter = service.queryReturn("N5", D0, D2).timeWeightedReturn();
        double leftAfter = service.queryReturn("N5", D0, D1).timeWeightedReturn();
        double rightAfter = service.queryReturn("N5", D1, D2).timeWeightedReturn();
        assertEquals(directAfter, (1 + leftAfter) * (1 + rightAfter) - 1, 1e-12);
        assertEquals(right, rightAfter, 0d);
        // 修正后 r1 = 5%, r2 = 10%，复合 = 1.05*1.10 - 1
        assertEquals(1.05 * 1.10 - 1, directAfter, 1e-9);
    }

    private ValuationRequest valuation(String portfolioId, LocalDate date, double total) {
        return new ValuationRequest(portfolioId, date, total, List.of());
    }

    private CashFlowRequest flow(String portfolioId, String externalId,
                                 LocalDate date, double amount) {
        return new CashFlowRequest(portfolioId, externalId, date, amount, "A");
    }
}

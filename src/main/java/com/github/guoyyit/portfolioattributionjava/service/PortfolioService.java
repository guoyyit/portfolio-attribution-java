package com.github.guoyyit.portfolioattributionjava.service;

import com.github.guoyyit.portfolioattributionjava.calc.AttributionCalculator;
import com.github.guoyyit.portfolioattributionjava.calc.AttributionResult;
import com.github.guoyyit.portfolioattributionjava.calc.CashFlowPoint;
import com.github.guoyyit.portfolioattributionjava.calc.ReturnCalculator;
import com.github.guoyyit.portfolioattributionjava.calc.SectorAttribution;
import com.github.guoyyit.portfolioattributionjava.calc.SectorSeries;
import com.github.guoyyit.portfolioattributionjava.domain.CashFlow;
import com.github.guoyyit.portfolioattributionjava.domain.SectorValuation;
import com.github.guoyyit.portfolioattributionjava.domain.ValuationSnapshot;
import com.github.guoyyit.portfolioattributionjava.error.ErrorCode;
import com.github.guoyyit.portfolioattributionjava.error.RejectionException;
import com.github.guoyyit.portfolioattributionjava.repo.CashFlowRepository;
import com.github.guoyyit.portfolioattributionjava.repo.SectorValuationRepository;
import com.github.guoyyit.portfolioattributionjava.repo.ValuationSnapshotRepository;
import com.github.guoyyit.portfolioattributionjava.web.dto.AttributionResponse;
import com.github.guoyyit.portfolioattributionjava.web.dto.CalculateReturnRequest;
import com.github.guoyyit.portfolioattributionjava.web.dto.CashFlowRequest;
import com.github.guoyyit.portfolioattributionjava.web.dto.ReturnResponse;
import com.github.guoyyit.portfolioattributionjava.web.dto.ValuationRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class PortfolioService {

    private static final double SECTOR_SUM_TOLERANCE = 1e-9;
    private static final double ZERO_FLOW_TOLERANCE = 1e-12;

    private final ValuationSnapshotRepository snapshotRepository;
    private final SectorValuationRepository sectorValuationRepository;
    private final CashFlowRepository cashFlowRepository;
    private final ReturnCalculator returnCalculator;
    private final AttributionCalculator attributionCalculator;

    private final ReentrantLock writeLock = new ReentrantLock();

    public PortfolioService(ValuationSnapshotRepository snapshotRepository,
                            SectorValuationRepository sectorValuationRepository,
                            CashFlowRepository cashFlowRepository,
                            ReturnCalculator returnCalculator,
                            AttributionCalculator attributionCalculator) {
        this.snapshotRepository = snapshotRepository;
        this.sectorValuationRepository = sectorValuationRepository;
        this.cashFlowRepository = cashFlowRepository;
        this.returnCalculator = returnCalculator;
        this.attributionCalculator = attributionCalculator;
    }

    @Transactional
    public void recordValuation(ValuationRequest request) {
        validateValuation(request);
        writeLock.lock();
        try {
            ValuationSnapshot snapshot = snapshotRepository
                    .findByPortfolioIdAndSnapshotDate(request.portfolioId(), request.date())
                    .orElseGet(() -> new ValuationSnapshot(
                            request.portfolioId(), request.date(), request.totalValue()));
            snapshot.setTotalValue(request.totalValue());
            snapshotRepository.save(snapshot);

            sectorValuationRepository
                    .findByPortfolioIdAndSnapshotDateOrderBySectorIdAsc(
                            request.portfolioId(), request.date())
                    .forEach(sectorValuationRepository::delete);
            sectorValuationRepository.flush();
            for (ValuationRequest.SectorValueDto sector : request.sectors()) {
                sectorValuationRepository.save(new SectorValuation(
                        request.portfolioId(), request.date(),
                        sector.sectorId(), sector.marketValue()));
            }
            snapshotRepository.flush();
            sectorValuationRepository.flush();
        } finally {
            writeLock.unlock();
        }
    }

    @Transactional
    public void recordCashFlow(CashFlowRequest request) {
        validateCashFlow(request);
        writeLock.lock();
        try {
            CashFlow flow = cashFlowRepository
                    .findByPortfolioIdAndExternalFlowId(
                            request.portfolioId(), request.externalFlowId())
                    .orElseGet(() -> new CashFlow(
                            request.portfolioId(), request.externalFlowId(),
                            request.date(), request.amount(), request.sectorId()));
            flow.setAmount(request.amount());
            flow.setFlowDate(request.date());
            flow.setSectorId(request.sectorId());
            cashFlowRepository.save(flow);
            cashFlowRepository.flush();
        } finally {
            writeLock.unlock();
        }
    }

    @Transactional(readOnly = true)
    public ReturnResponse queryReturn(String portfolioId, LocalDate start, LocalDate end) {
        validateRange(portfolioId, start, end);
        SectorSeries series = loadSeries(portfolioId, start, end);
        double twr = returnCalculator.timeWeightedReturn(series);
        double mwr = returnCalculator.moneyWeightedReturn(series);
        return new ReturnResponse(
                portfolioId, start.toString(), end.toString(), round6(twr), round6(mwr));
    }

    @Transactional(readOnly = true)
    public AttributionResponse queryAttribution(String portfolioId, String benchmarkId,
                                                LocalDate start, LocalDate end) {
        validateRange(portfolioId, start, end);
        requireText(benchmarkId, "benchmarkId");
        SectorSeries portfolio = loadSeries(portfolioId, start, end);
        SectorSeries benchmark = loadSeries(benchmarkId, start, end);
        AttributionResult result = attributionCalculator.attribute(portfolio, benchmark);

        List<AttributionResponse.SectorAttributionDto> sectorDtos = new ArrayList<>();
        for (SectorAttribution sector : result.sectors()) {
            sectorDtos.add(new AttributionResponse.SectorAttributionDto(
                    sector.sectorId(),
                    round6(sector.portfolioWeight()),
                    round6(sector.benchmarkWeight()),
                    round6(sector.portfolioSectorReturn()),
                    round6(sector.benchmarkSectorReturn()),
                    round6(sector.allocationEffect()),
                    round6(sector.selectionEffect()),
                    round6(sector.interactionEffect())));
        }
        return new AttributionResponse(
                portfolioId, benchmarkId, start.toString(), end.toString(),
                round6(result.portfolioReturn()),
                round6(result.benchmarkReturn()),
                round6(result.excessReturn()),
                round6(result.allocationEffect()),
                round6(result.selectionEffect()),
                round6(result.interactionEffect()),
                sectorDtos);
    }

    /**
     * 对显式输入的一次性计算：不读写持久状态，纯粹验证输入后计算 TWR/MWR。
     */
    public ReturnResponse calculateReturn(CalculateReturnRequest request) {
        if (request == null || request.valuations() == null
                || request.valuations().size() < 2) {
            throw reject(ErrorCode.INVALID_REQUEST,
                    "at least two valuations are required");
        }
        List<CalculateReturnRequest.ValuationPointDto> valuations = new ArrayList<>(
                request.valuations());
        valuations.sort((left, right) -> {
            if (left.date() == null || right.date() == null) {
                throw reject(ErrorCode.INVALID_REQUEST, "valuation date is required");
            }
            return left.date().compareTo(right.date());
        });
        LocalDate start = valuations.get(0).date();
        LocalDate end = valuations.get(valuations.size() - 1).date();

        LocalDate[] dates = new LocalDate[valuations.size()];
        double[] totals = new double[valuations.size()];
        for (int i = 0; i < valuations.size(); i++) {
            CalculateReturnRequest.ValuationPointDto point = valuations.get(i);
            if (point.totalValue() == null
                    || Double.isNaN(point.totalValue())
                    || Double.isInfinite(point.totalValue())) {
                throw reject(ErrorCode.INVALID_REQUEST,
                        "totalValue is required and must be finite at " + point.date());
            }
            if (point.totalValue() < 0d) {
                throw reject(ErrorCode.NEGATIVE_TOTAL_VALUE,
                        "totalValue must not be negative at " + point.date());
            }
            dates[i] = point.date();
            totals[i] = point.totalValue();
            if (i > 0 && dates[i].equals(dates[i - 1])) {
                throw reject(ErrorCode.INVALID_REQUEST,
                        "duplicate valuation at " + dates[i]);
            }
        }

        List<CashFlowPoint> flows = new ArrayList<>();
        if (request.cashFlows() != null) {
            Map<LocalDate, Double> seen = new HashMap<>();
            for (CalculateReturnRequest.CashFlowPointDto point : request.cashFlows()) {
                if (point == null || point.date() == null) {
                    throw reject(ErrorCode.INVALID_REQUEST, "cash flow date is required");
                }
                if (point.amount() == null
                        || Double.isNaN(point.amount())
                        || Double.isInfinite(point.amount())) {
                    throw reject(ErrorCode.INVALID_REQUEST,
                            "cash flow amount must be finite at " + point.date());
                }
                if (Math.abs(point.amount()) <= ZERO_FLOW_TOLERANCE) {
                    throw reject(ErrorCode.ZERO_CASH_FLOW,
                            "cash flow amount must not be zero at " + point.date());
                }
                seen.merge(point.date(), point.amount(), Double::sum);
            }
            returnCalculator.validateFlowWindow(new ArrayList<>(seen.keySet())
                    .stream()
                    .map(date -> new CashFlowPoint(date, seen.get(date), null))
                    .toList(), start, end);
            seen.forEach((date, amount) -> flows.add(new CashFlowPoint(date, amount, null)));
            flows.sort((left, right) -> left.date().compareTo(right.date()));
        }

        SectorSeries series = new SectorSeries(dates, totals, Map.of(), flows);
        double twr = returnCalculator.timeWeightedReturn(series);
        double mwr = returnCalculator.moneyWeightedReturn(series);
        return new ReturnResponse(
                "adhoc", start.toString(), end.toString(), round6(twr), round6(mwr));
    }

    private SectorSeries loadSeries(String portfolioId, LocalDate start, LocalDate end) {
        List<ValuationSnapshot> snapshots = snapshotRepository
                .findByPortfolioIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
                        portfolioId, start, end);
        if (snapshots.isEmpty()
                || !snapshots.get(0).getSnapshotDate().equals(start)
                || !snapshots.get(snapshots.size() - 1).getSnapshotDate().equals(end)) {
            throw new RejectionException(ErrorCode.MISSING_ENDPOINT_VALUATION,
                    "valuations are required at both endpoints " + start + " and " + end
                            + " for '" + portfolioId + "'");
        }

        LocalDate[] dates = new LocalDate[snapshots.size()];
        double[] totals = new double[snapshots.size()];
        for (int i = 0; i < snapshots.size(); i++) {
            dates[i] = snapshots.get(i).getSnapshotDate();
            totals[i] = snapshots.get(i).getTotalValue();
        }

        List<SectorValuation> sectorRows = sectorValuationRepository
                .findByPortfolioIdAndSnapshotDateBetweenOrderBySnapshotDateAscSectorIdAsc(
                        portfolioId, start, end);
        Map<String, double[]> sectorValues = new TreeMap<>();
        for (SectorValuation row : sectorRows) {
            double[] column = sectorValues.computeIfAbsent(
                    row.getSectorId(), ignored -> new double[snapshots.size()]);
            column[findDateIndex(dates, row.getSnapshotDate())] = row.getMarketValue();
        }

        List<CashFlowPoint> flowPoints = new ArrayList<>();
        List<CashFlow> flows = cashFlowRepository
                .findByPortfolioIdAndFlowDateBetweenOrderByFlowDateAscExternalFlowIdAsc(
                        portfolioId, start, end);
        for (CashFlow flow : flows) {
            if (flow.getFlowDate().isAfter(start) && !flow.getFlowDate().isAfter(end)) {
                flowPoints.add(new CashFlowPoint(
                        flow.getFlowDate(), flow.getAmount(), flow.getSectorId()));
            }
        }

        return new SectorSeries(dates, totals, sectorValues, flowPoints);
    }

    private int findDateIndex(LocalDate[] dates, LocalDate date) {
        for (int i = 0; i < dates.length; i++) {
            if (dates[i].equals(date)) {
                return i;
            }
        }
        throw new RejectionException(ErrorCode.INTERNAL_ERROR,
                "sector valuation date not found among snapshots: " + date);
    }

    private void validateValuation(ValuationRequest request) {
        requireText(request == null ? null : request.portfolioId(), "portfolioId");
        if (request.date() == null) {
            throw reject(ErrorCode.INVALID_REQUEST, "date is required");
        }
        if (request.totalValue() == null
                || Double.isNaN(request.totalValue())
                || Double.isInfinite(request.totalValue())) {
            throw reject(ErrorCode.INVALID_REQUEST,
                    "totalValue is required and must be finite");
        }
        if (request.totalValue() < 0d) {
            throw reject(ErrorCode.NEGATIVE_TOTAL_VALUE,
                    "totalValue must not be negative, got " + request.totalValue());
        }
        List<ValuationRequest.SectorValueDto> sectors =
                request.sectors() == null ? List.of() : request.sectors();
        double sectorSum = 0d;
        Map<String, Double> seen = new HashMap<>();
        for (ValuationRequest.SectorValueDto sector : sectors) {
            if (sector == null || isBlank(sector.sectorId())) {
                throw reject(ErrorCode.INVALID_REQUEST,
                        "each sector entry requires a non-blank sectorId");
            }
            if (sector.marketValue() == null
                    || Double.isNaN(sector.marketValue())
                    || Double.isInfinite(sector.marketValue())) {
                throw reject(ErrorCode.INVALID_REQUEST,
                        "sector marketValue must be a finite number for '"
                                + sector.sectorId() + "'");
            }
            if (sector.marketValue() < 0d) {
                throw reject(ErrorCode.NEGATIVE_SECTOR_VALUE,
                        "sector '" + sector.sectorId()
                                + "' marketValue must not be negative");
            }
            if (seen.put(sector.sectorId(), sector.marketValue()) != null) {
                throw reject(ErrorCode.INVALID_REQUEST,
                        "duplicate sector '" + sector.sectorId() + "' in one valuation");
            }
            sectorSum += sector.marketValue();
        }
        if (!sectors.isEmpty()
                && Math.abs(sectorSum - request.totalValue()) > SECTOR_SUM_TOLERANCE) {
            throw reject(ErrorCode.SECTOR_TOTAL_MISMATCH,
                    "sum of sector values " + sectorSum
                            + " does not equal totalValue " + request.totalValue());
        }
    }

    private void validateCashFlow(CashFlowRequest request) {
        requireText(request == null ? null : request.portfolioId(), "portfolioId");
        requireText(request.externalFlowId(), "externalFlowId");
        if (request.date() == null) {
            throw reject(ErrorCode.INVALID_REQUEST, "date is required");
        }
        if (request.amount() == null
                || Double.isNaN(request.amount())
                || Double.isInfinite(request.amount())) {
            throw reject(ErrorCode.INVALID_REQUEST,
                    "amount is required and must be finite");
        }
        if (Math.abs(request.amount()) <= ZERO_FLOW_TOLERANCE) {
            throw reject(ErrorCode.ZERO_CASH_FLOW,
                    "cash flow amount must not be zero (externalFlowId="
                            + request.externalFlowId() + ")");
        }
        if (isBlank(request.sectorId())) {
            throw reject(ErrorCode.SECTOR_CASH_FLOW_MISSING,
                    "sectorId is required for every cash flow (externalFlowId="
                            + request.externalFlowId() + ")");
        }
    }

    private void validateRange(String portfolioId, LocalDate start, LocalDate end) {
        requireText(portfolioId, "portfolioId");
        if (start == null || end == null) {
            throw reject(ErrorCode.INVALID_REQUEST, "start and end dates are required");
        }
        if (start.isAfter(end)) {
            throw reject(ErrorCode.INVALID_DATE_RANGE,
                    "start date " + start + " must not be after end date " + end);
        }
    }

    private void requireText(String value, String field) {
        if (isBlank(value)) {
            throw reject(ErrorCode.INVALID_REQUEST, field + " is required");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private RejectionException reject(ErrorCode code, String message) {
        return new RejectionException(code, message);
    }

    private double round6(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }
}

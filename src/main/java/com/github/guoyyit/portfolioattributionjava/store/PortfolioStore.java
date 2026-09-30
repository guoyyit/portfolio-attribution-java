package com.github.guoyyit.portfolioattributionjava.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.guoyyit.portfolioattributionjava.domain.CashFlow;
import com.github.guoyyit.portfolioattributionjava.domain.Kind;
import com.github.guoyyit.portfolioattributionjava.domain.PortfolioData;
import com.github.guoyyit.portfolioattributionjava.domain.ValuationSnapshot;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 数据存储：所有写入经事件日志持久化后再更新内存状态；
 * 查询总是返回一份不可变快照，绝不读到部分写入或新旧混合状态。
 */
@Component
public class PortfolioStore {

    private final EventLog eventLog;
    private final ObjectMapper objectMapper;

    private final Map<String, MutableState> states = new HashMap<>();
    private final Object writeLock = new Object();

    public PortfolioStore(EventLog eventLog, ObjectMapper objectMapper) {
        this.eventLog = eventLog;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void restore() {
        for (JsonNode event : eventLog.replay()) {
            applyEvent(event);
        }
    }

    public void saveValuation(String portfolioId, Kind kind, ValuationSnapshot snapshot) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "VALUATION");
        event.put("portfolioId", portfolioId);
        event.put("kind", kind.name());
        event.set("snapshot", objectMapper.valueToTree(snapshot));
        synchronized (writeLock) {
            eventLog.append(event);
            applyEvent(event);
        }
    }

    public void saveCashFlow(String portfolioId, CashFlow cashFlow) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "CASH_FLOW");
        event.put("portfolioId", portfolioId);
        event.set("cashFlow", objectMapper.valueToTree(cashFlow));
        synchronized (writeLock) {
            eventLog.append(event);
            applyEvent(event);
        }
    }

    /** 返回某组合当前不可变数据视图；不存在时返回空视图。 */
    public PortfolioData getData(String portfolioId) {
        synchronized (writeLock) {
            MutableState state = states.get(portfolioId);
            if (state == null) {
                return new PortfolioData(portfolioId, Map.of(), Map.of(), List.of());
            }
            return state.snapshot(portfolioId);
        }
    }

    private void applyEvent(JsonNode event) {
        String type = event.path("type").asText();
        String portfolioId = event.path("portfolioId").asText();
        MutableState state = states.computeIfAbsent(portfolioId, id -> new MutableState());
        switch (type) {
            case "VALUATION" -> {
                Kind kind = Kind.valueOf(event.path("kind").asText());
                ValuationSnapshot snapshot =
                        objectMapper.convertValue(event.path("snapshot"), ValuationSnapshot.class);
                if (kind == Kind.PORTFOLIO) {
                    state.portfolioValuations.put(snapshot.date(), snapshot);
                } else {
                    state.benchmarkValuations.put(snapshot.date(), snapshot);
                }
            }
            case "CASH_FLOW" -> {
                CashFlow cashFlow =
                        objectMapper.convertValue(event.path("cashFlow"), CashFlow.class);
                state.cashFlows.put(cashFlow.id(), cashFlow);
            }
            default -> throw new IllegalArgumentException("未知事件类型: " + type);
        }
    }

    private static final class MutableState {
        private final TreeMap<LocalDate, ValuationSnapshot> portfolioValuations = new TreeMap<>();
        private final TreeMap<LocalDate, ValuationSnapshot> benchmarkValuations = new TreeMap<>();
        private final Map<String, CashFlow> cashFlows = new HashMap<>();

        private PortfolioData snapshot(String portfolioId) {
            return new PortfolioData(
                    portfolioId,
                    Map.copyOf(portfolioValuations),
                    Map.copyOf(benchmarkValuations),
                    List.copyOf(cashFlows.values()));
        }
    }
}

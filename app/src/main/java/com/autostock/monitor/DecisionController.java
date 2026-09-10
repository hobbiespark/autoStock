package com.autostock.monitor;

import com.autostock.common.util.MarketConstants;
import com.autostock.monitor.view.SignalDecisionView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 종목 선정 이유(지평별) 조회 API (FE-6, PLAN.md ADR-10 확장표) —
 * {@code GET /api/decisions?date=YYYY-MM-DD}(기본값=오늘, KST).
 *
 * <p>{@link SignalDecisionRepository}(monitor 자체 소유)만 조회하므로 {@link PerformanceController}와
 * 같은 이유로 별도 Facade 없이 이 컨트롤러가 View DTO 변환까지 직접 한다(불필요한 분산 패턴
 * 금지, ARCHITECTURE.md 규칙 20). 서버는 그룹핑하지 않고 horizon→symbol 오름차순 평평한
 * 목록만 반환한다 — 지평별 탭 그룹핑은 FE 책임이다.
 */
@RestController
@RequestMapping("/api/decisions")
public class DecisionController {

    private static final Logger log = LoggerFactory.getLogger(DecisionController.class);

    private final SignalDecisionRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DecisionController(SignalDecisionRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @GetMapping
    public List<SignalDecisionView> decisions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate effectiveDate = date != null ? date : LocalDate.now(clock.withZone(MarketConstants.KST));
        return repository.findByTradeDateOrderByHorizonAscSymbolAsc(effectiveDate).stream()
                .map(this::toView)
                .toList();
    }

    private SignalDecisionView toView(SignalDecisionEntity entity) {
        return new SignalDecisionView(
                entity.getDecidedAt(),
                entity.getHorizon(),
                entity.getStrategyId(),
                entity.getSymbol(),
                entity.getConclusion(),
                entity.getReason(),
                parseMetrics(entity.getMetricsJson(), entity.getSymbol()));
    }

    /** metrics_json(TEXT) → Map&lt;String,String&gt; 역직렬화. 손상된 값이 있어도 화면 전체를 막지 않는다. */
    private Map<String, String> parseMetrics(String metricsJson, String symbol) {
        try {
            return objectMapper.readValue(metricsJson, new TypeReference<Map<String, String>>() { });
        } catch (Exception ex) {
            log.warn("SignalDecision metrics_json 역직렬화 실패(빈 맵으로 대체) — symbol={}", symbol, ex);
            return Map.of();
        }
    }
}

package com.autostock.monitor;

import com.autostock.monitor.view.SignalDecisionView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DecisionController — date 파라미터 기본값(오늘, KST)과 지정 날짜 조회, View DTO
 * 변환(metrics_json 역직렬화 포함)을 검증한다(FE-6, PLAN.md ADR-10 확장표).
 */
class DecisionControllerTest {

    private final SignalDecisionRepository repository = mock(SignalDecisionRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    // 2026-09-11T00:30:00Z = KST 2026-09-11 09:30 → KST 날짜는 2026-09-11 (PerformanceControllerTest와 동일 관례)
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-11T00:30:00Z"), ZoneOffset.UTC);
    private final DecisionController controller = new DecisionController(repository, objectMapper, clock);

    @Test
    void date_파라미터_생략시_KST_오늘_날짜로_조회한다() {
        when(repository.findByTradeDateOrderByHorizonAscSymbolAsc(any())).thenReturn(List.of());

        controller.decisions(null);

        verify(repository).findByTradeDateOrderByHorizonAscSymbolAsc(LocalDate.of(2026, 9, 11));
    }

    @Test
    void date_파라미터를_지정하면_해당_날짜로_조회한다() {
        LocalDate requested = LocalDate.of(2026, 8, 20);
        when(repository.findByTradeDateOrderByHorizonAscSymbolAsc(requested)).thenReturn(List.of());

        controller.decisions(requested);

        verify(repository).findByTradeDateOrderByHorizonAscSymbolAsc(requested);
    }

    @Test
    void 조회_결과가_없으면_빈_목록을_반환한다() {
        when(repository.findByTradeDateOrderByHorizonAscSymbolAsc(any())).thenReturn(List.of());

        List<SignalDecisionView> result = controller.decisions(LocalDate.of(2026, 9, 1));

        assertTrue(result.isEmpty());
    }

    @Test
    void 엔티티를_View_DTO로_변환하고_metrics_json을_맵으로_역직렬화한다() throws Exception {
        String metricsJson = objectMapper.writeValueAsString(
                java.util.Map.of("momentumReturnPct", "3.21", "regimeStatus", "ON"));
        SignalDecisionEntity entity = new SignalDecisionEntity(
                Instant.parse("2026-09-11T00:05:00Z"), LocalDate.of(2026, 9, 11),
                "MID", "C3-MOMENTUM", "005930", "BUY",
                "모멘텀 상승 전환 + 국면 ON + 미보유 — 매수 시그널 발행", metricsJson);
        when(repository.findByTradeDateOrderByHorizonAscSymbolAsc(any())).thenReturn(List.of(entity));

        List<SignalDecisionView> result = controller.decisions(LocalDate.of(2026, 9, 11));

        assertEquals(1, result.size());
        SignalDecisionView view = result.get(0);
        assertEquals("MID", view.horizon());
        assertEquals("C3-MOMENTUM", view.strategyId());
        assertEquals("005930", view.symbol());
        assertEquals("BUY", view.conclusion());
        assertEquals("3.21", view.metrics().get("momentumReturnPct"));
        assertEquals("ON", view.metrics().get("regimeStatus"));
    }

    @Test
    void metrics_json이_손상돼도_전체_목록_조회는_실패하지_않고_빈_맵으로_대체한다() {
        SignalDecisionEntity entity = new SignalDecisionEntity(
                Instant.parse("2026-09-11T00:05:00Z"), LocalDate.of(2026, 9, 11),
                "MID", "C3-MOMENTUM", "005930", "SKIP", "테스트 사유", "{손상된 json");
        when(repository.findByTradeDateOrderByHorizonAscSymbolAsc(any())).thenReturn(List.of(entity));

        List<SignalDecisionView> result = controller.decisions(LocalDate.of(2026, 9, 11));

        assertEquals(1, result.size());
        assertTrue(result.get(0).metrics().isEmpty());
    }
}

package com.autostock.monitor;

import com.autostock.common.event.SignalDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SignalDecisionListener — {@link SignalDecision} 이벤트를 {@code signal_decisions}에
 * 영속화하는지(FE-6, PLAN.md ADR-10 확장표), decidedAt(Instant)을 KST 기준 trade_date로
 * 정확히 환산하는지, metrics 맵을 JSON으로 직렬화하는지, 저장 실패를 삼키고 예외를 전파하지
 * 않는지(EventAuditListener와 같은 원칙)를 검증한다.
 */
class SignalDecisionListenerTest {

    private final SignalDecisionRepository repository = mock(SignalDecisionRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SignalDecisionListener listener = new SignalDecisionListener(repository, objectMapper);

    // 2026-09-11T00:05:00Z = KST 2026-09-11 09:05 (C3의 09:05 스케줄과 동일 시각대)
    private static final Instant DECIDED_AT = Instant.parse("2026-09-11T00:05:00Z");

    @Test
    void SignalDecision을_받으면_trade_date를_KST로_환산해_저장한다() {
        SignalDecision event = new SignalDecision(
                "MID", "C3-MOMENTUM", "005930", "BUY",
                "모멘텀 상승 전환 + 국면 ON + 미보유 — 매수 시그널 발행",
                Map.of("momentumLookbackN", "120", "regimeStatus", "ON"),
                DECIDED_AT);

        listener.on(event);

        var captor = org.mockito.ArgumentCaptor.forClass(SignalDecisionEntity.class);
        verify(repository).save(captor.capture());
        SignalDecisionEntity saved = captor.getValue();
        assertEquals(LocalDate.of(2026, 9, 11), saved.getTradeDate());
        assertEquals(DECIDED_AT, saved.getDecidedAt());
        assertEquals("MID", saved.getHorizon());
        assertEquals("C3-MOMENTUM", saved.getStrategyId());
        assertEquals("005930", saved.getSymbol());
        assertEquals("BUY", saved.getConclusion());
        assertEquals("모멘텀 상승 전환 + 국면 ON + 미보유 — 매수 시그널 발행", saved.getReason());
        assertTrue(saved.getMetricsJson().contains("\"regimeStatus\":\"ON\""));
    }

    @Test
    void 자정_직전_UTC_시각도_KST_날짜로_정확히_넘어간다() {
        // 2026-09-10T15:00:00Z = KST 2026-09-11 00:00 — UTC 날짜(9/10)와 KST 날짜(9/11)가
        // 갈리는 경계 케이스. trade_date 계산이 실제로 KST를 쓰는지(UTC로 잘못 계산하면
        // 9/10이 됨) 확인한다.
        Instant boundary = Instant.parse("2026-09-10T15:00:00Z");
        SignalDecision event = new SignalDecision(
                "MID", "C3-MOMENTUM", "000660", "SKIP", "국면 OFF", Map.of(), boundary);

        listener.on(event);

        var captor = org.mockito.ArgumentCaptor.forClass(SignalDecisionEntity.class);
        verify(repository).save(captor.capture());
        assertEquals(LocalDate.of(2026, 9, 11), captor.getValue().getTradeDate());
    }

    @Test
    void 저장_실패는_삼키고_예외를_전파하지_않는다() {
        when(repository.save(any())).thenThrow(new RuntimeException("DB 오류(테스트)"));
        SignalDecision event = new SignalDecision(
                "MID", "C3-MOMENTUM", "005930", "REJECTED", "일 주문 한도 초과 — 거부",
                Map.of(), DECIDED_AT);

        listener.on(event); // 예외가 던져지지 않아야 함(assertDoesNotThrow 없이도 실패 시 테스트가 곧바로 실패)

        verify(repository, times(1)).save(any());
        verify(repository, never()).findByTradeDateOrderByHorizonAscSymbolAsc(any());
    }
}

package com.autostock.risk;

import com.autostock.common.event.KillSwitchChanged;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

/**
 * KillSwitch 이벤트 발행 검증 — monitor 모듈의 텔레그램 CRITICAL 알림이 이 이벤트에 의존하므로
 * "상태가 실제로 바뀔 때만 정확히 한 번" 발행되는지가 중요하다.
 */
class KillSwitchTest {

    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher publisher = published::add;
    private RiskStateStore store;
    private KillSwitch killSwitch;

    @BeforeEach
    void setUp() {
        store = mock(RiskStateStore.class);
        killSwitch = new KillSwitch(publisher, store, Clock.systemUTC());
    }

    @Test
    void engage하면_KillSwitchChanged_engaged_true가_발행된다() {
        killSwitch.engage("일 손실 한도 도달");

        assertEquals(1, published.size());
        KillSwitchChanged event = (KillSwitchChanged) published.get(0);
        assertTrue(event.engaged());
        assertEquals("일 손실 한도 도달", event.reason());
    }

    @Test
    void release하면_KillSwitchChanged_engaged_false가_발행된다() {
        killSwitch.engage("테스트");
        published.clear();

        killSwitch.release("operator-1");

        assertEquals(1, published.size());
        KillSwitchChanged event = (KillSwitchChanged) published.get(0);
        assertFalse(event.engaged());
        assertEquals("operator-1", event.reason());
    }

    @Test
    void 이미_작동_중이면_중복_engage는_이벤트를_다시_발행하지_않는다() {
        killSwitch.engage("최초");
        killSwitch.engage("중복 시도");

        assertEquals(1, published.size());
    }

    @Test
    void 이미_해제_상태면_release는_이벤트를_발행하지_않는다() {
        killSwitch.release("operator-1");

        assertTrue(published.isEmpty());
    }

    // ---- Phase 0.2: 영속화·재기동 복원 (aiDoc/risk-state-persistence.md) ----

    private static RiskStateEntity savedState(boolean engaged, String reason) {
        RiskStateEntity state = RiskStateEntity.newSingleton(Instant.parse("2026-10-01T05:20:00Z"));
        if (engaged) {
            state.engage(reason, Instant.parse("2026-10-01T05:20:00Z"));
        }
        return state;
    }

    @Test
    void engage하면_작동_상태를_저장한다() {
        killSwitch.engage("시세 단절 200초");

        verify(store).saveEngaged(eq("시세 단절 200초"), any());
    }

    @Test
    void release하면_해제_상태를_저장한다() {
        killSwitch.engage("테스트");

        killSwitch.release("dashboard");

        verify(store).saveReleased(eq("dashboard"), any());
    }

    @Test
    void 상태가_바뀌지_않으면_저장하지_않는다() {
        killSwitch.release("dashboard"); // 이미 해제 상태

        verify(store, never()).saveReleased(anyString(), any());
    }

    @Test
    void 저장이_실패해도_작동은_유지되고_알림도_나간다() {
        doThrow(new RuntimeException("DB 연결 끊김")).when(store).saveEngaged(anyString(), any());

        killSwitch.engage("일 손실 한도 도달");

        assertTrue(killSwitch.isEngaged()); // fail-safe: 메모리 상태 우선
        assertEquals(1, published.size());
    }

    @Test
    void 재기동_시_작동_상태였으면_작동으로_시작하고_기동_완료_때_복원_알림을_1회_보낸다() {
        when(store.loadKillSwitch()).thenReturn(Optional.of(savedState(true, "시세 단절 200초")));

        killSwitch.restorePersistedState();

        assertTrue(killSwitch.isEngaged());
        assertTrue(published.isEmpty()); // 알림은 기동 완료 후

        killSwitch.announceRestored();
        killSwitch.announceRestored();

        assertEquals(1, published.size());
        KillSwitchChanged event = (KillSwitchChanged) published.get(0);
        assertTrue(event.engaged());
        assertEquals("재기동 복원: 시세 단절 200초", event.reason());
    }

    @Test
    void 재기동_시_해제_상태였으면_해제로_시작하고_알림이_없다() {
        when(store.loadKillSwitch()).thenReturn(Optional.of(savedState(false, null)));

        killSwitch.restorePersistedState();
        killSwitch.announceRestored();

        assertFalse(killSwitch.isEngaged());
        assertTrue(published.isEmpty());
    }

    @Test
    void 저장된_상태가_없으면_해제로_시작한다() {
        when(store.loadKillSwitch()).thenReturn(Optional.empty());

        killSwitch.restorePersistedState();

        assertFalse(killSwitch.isEngaged());
    }

    @Test
    void 상태를_읽지_못하면_안전을_위해_작동으로_시작한다() {
        when(store.loadKillSwitch()).thenThrow(new RuntimeException("DB 연결 끊김"));

        killSwitch.restorePersistedState();
        killSwitch.announceRestored();

        assertTrue(killSwitch.isEngaged());
        assertEquals(1, published.size());
        assertTrue(((KillSwitchChanged) published.get(0)).reason().startsWith("재기동 복원: 상태 복원 실패"));
    }

    @Test
    void 복원_알림_전에_사람이_해제하면_복원_알림을_보내지_않는다() {
        when(store.loadKillSwitch()).thenReturn(Optional.of(savedState(true, "텔레그램 원격 명령")));
        killSwitch.restorePersistedState();

        killSwitch.release("dashboard");
        published.clear();
        killSwitch.announceRestored();

        assertFalse(killSwitch.isEngaged());
        assertTrue(published.isEmpty());
    }
}

package com.autostock.monitor;

import com.autostock.common.event.KillSwitchChanged;
import com.autostock.trading.TradingProperties;
import com.autostock.trading.ReconciliationService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * TradingSystemManager 검증 — start/stop 비동기 흐름, LIVE reconcile 실패,
 * 킬스위치 연동, 불법 전이 예외. ReconciliationService는 mock(실제 브로커 호출 없음).
 *
 * <p>start()/stop()은 Backend Source of Truth 원칙상 반환은 즉시(STARTING/STOPPING)
 * 오고 실제 완료는 가상 스레드에서 비동기로 진행된다 — 그래서 테스트는 {@link #awaitStatus}로
 * 짧게 폴링하며 최종 상태 도달을 기다린다(Awaitility 미도입 — 이 정도 대기는 직접 구현으로 충분).
 */
class TradingSystemManagerTest {

    private static TradingProperties simProperties() {
        return new TradingProperties(TradingProperties.Mode.SIM, Duration.ofMinutes(5));
    }

    private static TradingProperties liveProperties() {
        return new TradingProperties(TradingProperties.Mode.LIVE, Duration.ofMinutes(5));
    }

    @Test
    void SIM_모드_시작은_reconcile_없이_RUNNING까지_도달() throws InterruptedException {
        ReconciliationService reconciliation = mock(ReconciliationService.class);
        TradingSystemManager manager = new TradingSystemManager(simProperties(), reconciliation);

        assertEquals(TradingSystemStatus.STARTING, manager.start(), "start()는 즉시 STARTING을 반환해야 함");
        awaitStatus(manager, TradingSystemStatus.RUNNING);

        verify(reconciliation, never()).reconcile();
    }

    @Test
    void LIVE_모드_시작은_reconcile_성공하면_RUNNING까지_도달() throws InterruptedException {
        ReconciliationService reconciliation = mock(ReconciliationService.class);
        TradingSystemManager manager = new TradingSystemManager(liveProperties(), reconciliation);

        manager.start();
        awaitStatus(manager, TradingSystemStatus.RUNNING);

        verify(reconciliation, times(1)).reconcile();
    }

    @Test
    void LIVE_모드_reconcile_실패하면_ERROR로_전이() throws InterruptedException {
        ReconciliationService reconciliation = mock(ReconciliationService.class);
        doThrow(new RuntimeException("브로커 연결 실패(테스트)")).when(reconciliation).reconcile();
        TradingSystemManager manager = new TradingSystemManager(liveProperties(), reconciliation);

        manager.start();
        awaitStatus(manager, TradingSystemStatus.ERROR);
    }

    @Test
    void 정지_명령은_RUNNING에서_STOPPED까지_도달() throws InterruptedException {
        ReconciliationService reconciliation = mock(ReconciliationService.class);
        TradingSystemManager manager = new TradingSystemManager(simProperties(), reconciliation);
        manager.start();
        awaitStatus(manager, TradingSystemStatus.RUNNING);

        assertEquals(TradingSystemStatus.STOPPING, manager.stop(), "stop()은 즉시 STOPPING을 반환해야 함");
        awaitStatus(manager, TradingSystemStatus.STOPPED);
    }

    @Test
    void 킬스위치_작동시_RUNNING에서_DEGRADED로_해제시_RUNNING복귀() throws InterruptedException {
        ReconciliationService reconciliation = mock(ReconciliationService.class);
        TradingSystemManager manager = new TradingSystemManager(simProperties(), reconciliation);
        manager.start();
        awaitStatus(manager, TradingSystemStatus.RUNNING);

        manager.on(new KillSwitchChanged(true, "테스트 작동", Instant.now()));
        assertEquals(TradingSystemStatus.DEGRADED, manager.status());

        manager.on(new KillSwitchChanged(false, "테스트 해제", Instant.now()));
        assertEquals(TradingSystemStatus.RUNNING, manager.status());
    }

    @Test
    void STOPPED에서_킬스위치_이벤트는_무시된다() {
        // STOPPED 상태에서 킬스위치가 작동해도(예: 다른 원인) RUNNING이 아니므로
        // DEGRADED로 전이하지 않는다 — compareAndSet(RUNNING, DEGRADED)가 실패해 조용히 무시.
        ReconciliationService reconciliation = mock(ReconciliationService.class);
        TradingSystemManager manager = new TradingSystemManager(simProperties(), reconciliation);

        manager.on(new KillSwitchChanged(true, "테스트", Instant.now()));

        assertEquals(TradingSystemStatus.STOPPED, manager.status());
    }

    @Test
    void STOPPED에서_stop_호출은_불법_전이_예외() {
        ReconciliationService reconciliation = mock(ReconciliationService.class);
        TradingSystemManager manager = new TradingSystemManager(simProperties(), reconciliation);

        assertThrows(IllegalStateException.class, manager::stop);
    }

    @Test
    void RUNNING에서_start_재호출은_불법_전이_예외() throws InterruptedException {
        ReconciliationService reconciliation = mock(ReconciliationService.class);
        TradingSystemManager manager = new TradingSystemManager(simProperties(), reconciliation);
        manager.start();
        awaitStatus(manager, TradingSystemStatus.RUNNING);

        assertThrows(IllegalStateException.class, manager::start);
    }

    /** 비동기(가상 스레드) 전이가 끝날 때까지 짧게 폴링 — 최대 2초, 실패 시 명시적으로 fail(). */
    private void awaitStatus(TradingSystemManager manager, TradingSystemStatus expected) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(2);
        while (manager.status() != expected) {
            if (Instant.now().isAfter(deadline)) {
                fail("상태가 " + expected + "에 도달하지 못함 (현재: " + manager.status() + ")");
            }
            Thread.sleep(10);
        }
    }
}

package com.autostock.monitor;

import com.autostock.market.MarketSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 절전 복귀 감지·절전 방지 전환 검증. 2026-09-30 실측: 10:08(장중)에 멈춘 앱이 17:51(장외)에 깨어났다.
 */
class SleepGuardTest {

    private static final Instant T0 = Instant.parse("2026-09-30T01:08:00Z");   // 10:08 KST

    private final MarketSessionService marketSession = mock(MarketSessionService.class);
    private final SystemSleepBlocker sleepBlocker = mock(SystemSleepBlocker.class);
    private final Notifier notifier = mock(Notifier.class);
    private MutableClock clock;
    private SleepGuard guard;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        guard = new SleepGuard(marketSession, sleepBlocker, notifier, clock, true);
    }

    @Test
    void 장중에_멈췄다가_장외에_깨어나면_WARN_알림을_보낸다() {
        when(marketSession.isActive()).thenReturn(true);
        guard.tick();                                            // 10:08 장중
        when(marketSession.isActive()).thenReturn(false);
        clock.advance(Duration.ofHours(7).plusMinutes(43));
        guard.tick();                                            // 17:51 장외

        verify(notifier).notify(eq(NoticeLevel.WARN), contains("7시간 43분"));
    }

    @Test
    void 장외에_잠들었다_장외에_깨어나면_알림을_보내지_않는다() {
        when(marketSession.isActive()).thenReturn(false);
        guard.tick();
        clock.advance(Duration.ofHours(10));
        guard.tick();

        verify(notifier, never()).notify(eq(NoticeLevel.WARN), anyString());
    }

    @Test
    void 평소_틱_간격에서는_복귀로_보지_않는다() {
        when(marketSession.isActive()).thenReturn(true);
        guard.tick();
        clock.advance(SleepGuard.RESUME_GAP.minusSeconds(1));
        guard.tick();

        verify(notifier, never()).notify(eq(NoticeLevel.WARN), anyString());
    }

    @Test
    void 장중에는_절전을_막고_장외에는_푼다() {
        when(marketSession.isActive()).thenReturn(true);
        guard.tick();
        verify(sleepBlocker).preventSleep(true);

        when(marketSession.isActive()).thenReturn(false);
        guard.tick();
        verify(sleepBlocker).preventSleep(false);
    }

    @Test
    void 거래일_장전_준비_구간에도_미리_절전을_막는다() {
        guard = new SleepGuard(marketSession, sleepBlocker, notifier, clock, true, "07:30");
        when(marketSession.isActive()).thenReturn(false);
        when(marketSession.isPreOpen(java.time.LocalTime.of(7, 30))).thenReturn(true);   // 07:30~08:30, 거래일

        guard.tick();
        verify(sleepBlocker).preventSleep(true);

        when(marketSession.isPreOpen(java.time.LocalTime.of(7, 30))).thenReturn(false);  // 밤·휴장일
        guard.tick();
        verify(sleepBlocker).preventSleep(false);
    }

    @Test
    void keep_awake를_끄면_절전_방지를_요청하지_않는다() {
        guard = new SleepGuard(marketSession, sleepBlocker, notifier, clock, false);
        when(marketSession.isActive()).thenReturn(true);

        guard.tick();

        verify(sleepBlocker, never()).preventSleep(true);
    }

    // ── 배터리 감시(2026-10-12 — 노트북이 배터리로 장을 맞아 대기 정지) ─────────────────────────────

    private final AtomicReference<Optional<PowerSource.PowerStatus>> power = new AtomicReference<>(Optional.empty());

    private SleepGuard guardWithPower() {
        return new SleepGuard(marketSession, sleepBlocker, notifier, clock, true, "07:30", power::get);
    }

    private void powerIs(boolean onAc, Integer percent) {
        power.set(Optional.of(new PowerSource.PowerStatus(onAc, percent)));
    }

    @Test
    void 장_대응_시간에_배터리가_두_틱_연속이면_WARN을_한_번_보낸다() {
        SleepGuard guard = guardWithPower();
        when(marketSession.isActive()).thenReturn(true);
        powerIs(false, 73);

        guard.tick();                                          // 첫 틱 — 순간 끊김일 수 있어 기다린다
        verify(notifier, never()).notify(eq(NoticeLevel.WARN), anyString());
        guard.tick();
        guard.tick();

        verify(notifier, times(1)).notify(eq(NoticeLevel.WARN), contains("배터리 73%"));
    }

    @Test
    void 순간_끊김은_알리지_않는다() {
        SleepGuard guard = guardWithPower();
        when(marketSession.isActive()).thenReturn(true);
        powerIs(false, 90);
        guard.tick();
        powerIs(true, 90);                                     // 10/8 실측처럼 몇 초 만에 다시 연결
        guard.tick();

        verify(notifier, never()).notify(any(), anyString());
    }

    @Test
    void 잔량이_30퍼센트_이하면_CRITICAL을_한_번_보내고_충전기가_꽂히면_INFO로_알린다() {
        SleepGuard guard = guardWithPower();
        when(marketSession.isActive()).thenReturn(true);
        powerIs(false, 31);
        guard.tick();
        guard.tick();                                          // WARN
        powerIs(false, 30);
        guard.tick();                                          // CRITICAL
        powerIs(false, 25);
        guard.tick();                                          // 이미 알렸다 — 반복하지 않는다
        powerIs(true, 26);
        guard.tick();                                          // 다시 연결 INFO

        verify(notifier, times(1)).notify(eq(NoticeLevel.WARN), anyString());
        verify(notifier, times(1)).notify(eq(NoticeLevel.CRITICAL), contains("배터리 30%"));
        verify(notifier, times(1)).notify(eq(NoticeLevel.INFO), contains("충전기 다시 연결됨 — 배터리 26%"));
    }

    @Test
    void 장외에는_배터리여도_알리지_않고_장전_준비가_시작되면_알린다() {
        SleepGuard guard = guardWithPower();
        when(marketSession.isActive()).thenReturn(false);
        when(marketSession.isPreOpen(LocalTime.of(7, 30))).thenReturn(false);   // 밤·휴장일
        powerIs(false, 87);
        guard.tick();
        guard.tick();
        verify(notifier, never()).notify(any(), anyString());

        when(marketSession.isPreOpen(LocalTime.of(7, 30))).thenReturn(true);    // 거래일 07:30
        guard.tick();

        verify(notifier, times(1)).notify(eq(NoticeLevel.WARN), contains("배터리 87%"));
    }

    @Test
    void 전원_조회가_실패해도_절전_대응은_그대로다() {
        SleepGuard guard = new SleepGuard(marketSession, sleepBlocker, notifier, clock, true, "07:30", () -> {
            throw new IllegalStateException("조회 실패");
        });
        when(marketSession.isActive()).thenReturn(true);

        guard.tick();

        verify(sleepBlocker).preventSleep(true);
        verify(notifier, never()).notify(any(), anyString());
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}

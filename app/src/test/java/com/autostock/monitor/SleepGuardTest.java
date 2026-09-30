package com.autostock.monitor;

import com.autostock.market.MarketSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    void keep_awake를_끄면_절전_방지를_요청하지_않는다() {
        guard = new SleepGuard(marketSession, sleepBlocker, notifier, clock, false);
        when(marketSession.isActive()).thenReturn(true);

        guard.tick();

        verify(sleepBlocker, never()).preventSleep(true);
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

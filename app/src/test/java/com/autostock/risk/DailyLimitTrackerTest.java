package com.autostock.risk;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DailyLimitTracker — 일 주문 한도와 KST 자정 롤오버(UTC 15:00)를 주입된 시계로 검증한다.
 */
class DailyLimitTrackerTest {

    /** KST 09-29 23:59:59 = UTC 09-29 14:59:59. */
    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-29T14:59:59Z"));

    private DailyLimitTracker tracker(int dailyMaxOrders) {
        RiskProperties properties = mock(RiskProperties.class);
        when(properties.dailyMaxOrders()).thenReturn(dailyMaxOrders);
        return new DailyLimitTracker(properties, clock);
    }

    @Test
    void 한도까지는_허용하고_한도_다음_주문은_거부한다() {
        DailyLimitTracker tracker = tracker(2);

        assertTrue(tracker.tryAcquireOrderSlot());
        assertTrue(tracker.tryAcquireOrderSlot());
        assertFalse(tracker.tryAcquireOrderSlot());
    }

    @Test
    void KST_자정이_지나면_카운터가_초기화된다() {
        DailyLimitTracker tracker = tracker(1);
        assertTrue(tracker.tryAcquireOrderSlot());
        assertFalse(tracker.tryAcquireOrderSlot());

        clock.advance(Duration.ofSeconds(1)); // KST 09-30 00:00:00 (UTC 날짜는 아직 09-29)

        assertEquals(0, tracker.todayOrderCount());
        assertTrue(tracker.tryAcquireOrderSlot());
    }

    @Test
    void UTC_자정은_KST_날짜_경계가_아니다() {
        clock.advance(Duration.ofHours(1)); // KST 09-30 01:00 시작
        DailyLimitTracker tracker = tracker(1);
        assertTrue(tracker.tryAcquireOrderSlot());

        clock.advance(Duration.ofHours(9)); // UTC 09-30 00:00 = KST 09-30 10:00 — 같은 KST 날

        assertEquals(1, tracker.todayOrderCount());
        assertFalse(tracker.tryAcquireOrderSlot());
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
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return zone;
                }

                @Override
                public Clock withZone(ZoneId z) {
                    return MutableClock.this.withZone(z);
                }

                @Override
                public Instant instant() {
                    return now;
                }
            };
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

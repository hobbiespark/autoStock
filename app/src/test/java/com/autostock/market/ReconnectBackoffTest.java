package com.autostock.market;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 재연결 지수 백오프 — B3 추출 전에는 실제 연결 없이 부를 수 없어 테스트가 없던 부분(aiDoc/large-classes.md).
 */
class ReconnectBackoffTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-26T00:00:00Z"));   // 토요일(주말 점검)
    private final ReconnectBackoff backoff = new ReconnectBackoff(clock);

    @Test
    void 처음에는_바로_시도할_수_있다() {
        assertTrue(backoff.readyToAttempt());
        assertEquals(0, backoff.consecutiveFailures());
    }

    @Test
    void 실패할수록_10초부터_두_배씩_늘고_5분에서_멈춘다() {
        long[] expected = {10, 20, 40, 80, 160, 300, 300, 300};
        for (int i = 0; i < expected.length; i++) {
            backoff.recordFailure();
            assertEquals(expected[i], backoff.currentDelaySeconds(), (i + 1) + "회 연속 실패");
        }
    }

    @Test
    void 대기_간격이_지나기_전에는_시도하지_않는다() {
        backoff.recordFailure();
        backoff.recordFailure();   // 20초

        clock.advance(Duration.ofSeconds(19));
        assertFalse(backoff.readyToAttempt());
        clock.advance(Duration.ofSeconds(1));
        assertTrue(backoff.readyToAttempt());
    }

    @Test
    void 스택트레이스는_연속_3회까지만_남긴다() {
        for (int i = 1; i <= ReconnectBackoff.STACKTRACE_UNTIL; i++) {
            backoff.recordFailure();
            assertTrue(backoff.logStackTrace(), i + "회");
        }
        backoff.recordFailure();
        assertFalse(backoff.logStackTrace(), "4회부터는 한 줄 요약");
    }

    @Test
    void 초기화하면_즉시_시도하고_다음_실패는_다시_10초부터다() {
        for (int i = 0; i < 6; i++) {
            backoff.recordFailure();
        }
        backoff.reset();

        assertTrue(backoff.readyToAttempt());
        backoff.recordFailure();
        assertEquals(10, backoff.currentDelaySeconds());
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

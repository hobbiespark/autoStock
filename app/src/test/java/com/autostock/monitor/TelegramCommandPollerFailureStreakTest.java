package com.autostock.monitor;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 텔레그램 폴링 연속 실패 요약(L-6) — 10/13 13:50~16:00 텔레그램 서버에 닿지 않는 동안 ERROR와 스택이 81번 찍혔다.
 */
class TelegramCommandPollerFailureStreakTest {

    /** 2026-10-13 13:50:00 KST. */
    private static final Instant T0 = Instant.parse("2026-10-13T04:50:00Z");

    @Test
    void 처음_실패만_남기고_이어지는_실패는_10분마다_한_줄로_요약한다() {
        TelegramCommandPoller.FailureStreak streak = new TelegramCommandPoller.FailureStreak();

        String first = streak.fail(T0, "WebClientRequestException: connection timed out after 5000 ms");
        assertTrue(first.startsWith("텔레그램 명령 폴링 실패 — WebClientRequestException"), first);
        assertNull(streak.fail(T0.plusSeconds(10), "같은 오류"));
        assertNull(streak.fail(T0.plus(Duration.ofMinutes(9)), "같은 오류"));

        String summary = streak.fail(T0.plus(Duration.ofMinutes(10)), "같은 오류");
        assertEquals("텔레그램 명령 폴링 계속 실패 — 4회째(13:50부터), 마지막: 같은 오류", summary);
    }

    @Test
    void 복구되면_실패_횟수와_구간을_한_줄로_남기고_처음부터_센다() {
        TelegramCommandPoller.FailureStreak streak = new TelegramCommandPoller.FailureStreak();
        streak.fail(T0, "오류");
        streak.fail(T0.plusSeconds(20), "오류");

        assertEquals("텔레그램 명령 폴링 복구 — 2회 실패(13:50~16:00, 130분)",
                streak.succeed(T0.plus(Duration.ofMinutes(130))));
        assertNull(streak.succeed(T0.plus(Duration.ofMinutes(131))), "실패가 없으면 남길 것이 없다");
        assertTrue(streak.fail(T0.plus(Duration.ofMinutes(140)), "새 오류").startsWith("텔레그램 명령 폴링 실패"));
    }

    @Test
    void 두_번째_실패부터_다음_시도까지_15초_30초_60초로_간격을_벌린다() {
        TelegramCommandPoller.FailureStreak streak = new TelegramCommandPoller.FailureStreak();

        streak.fail(T0, "오류");
        assertFalse(streak.shouldWait(T0.plusSeconds(5)), "첫 실패 뒤에는 다음 주기(5초)에 바로 다시 본다");

        streak.fail(T0, "오류");
        assertEquals(Duration.ofSeconds(15), streak.backoff());
        assertTrue(streak.shouldWait(T0.plusSeconds(10)));
        assertFalse(streak.shouldWait(T0.plusSeconds(15)));

        streak.fail(T0, "오류");
        assertEquals(Duration.ofSeconds(30), streak.backoff());
        streak.fail(T0, "오류");
        assertEquals(Duration.ofSeconds(60), streak.backoff());
        streak.fail(T0, "오류");
        assertEquals(Duration.ofSeconds(60), streak.backoff(), "60초가 상한이다");

        streak.succeed(T0.plusSeconds(60));
        assertFalse(streak.shouldWait(T0.plusSeconds(61)));
    }
}

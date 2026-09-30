package com.autostock.market;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WS 재연결 지수 백오프 — 연속 실패가 쌓일수록 재시도 간격을 늘리고(10초 → 2배씩 → 최대 5분), 스택트레이스는 처음
 * {@link #STACKTRACE_UNTIL}회만 남기게 판단한다. {@link KiwoomWebSocketClient}에서 추출(B3, aiDoc/large-classes.md) —
 * 실제 연결 없이 간격 계산을 테스트하기 위해서다.
 *
 * <p>배경(운영 2일차, 2026-09-12 실측): 토요일 기동 시 키움 모의 서버가 주말 점검으로 닫혀(토큰 엔드포인트가 JSON 대신
 * {@code text/html} 점검 페이지, WS 업그레이드 502) watchdog이 10초마다 재연결을 시도하며 전체 스택트레이스를 ERROR로
 * 찍었다 — 주말 이틀이면 로그 수만 줄. 서버가 닫힌 동안의 재시도는 아무것도 복구하지 못한다.
 *
 * <p>연결 콜백(다른 스레드)과 watchdog이 함께 부르므로 상태는 원자 변수로 둔다.
 */
final class ReconnectBackoff {

    /** 첫 실패 뒤 대기. */
    static final long INITIAL_SECONDS = 10;
    /** 상한 — 서버 점검처럼 장시간 닫힌 경우에도 5분마다는 확인한다. */
    static final long MAX_SECONDS = 300;
    /** 전체 스택트레이스를 남기는 최대 연속 실패 횟수 — 이후에는 한 줄 요약만. */
    static final int STACKTRACE_UNTIL = 3;

    private final Clock clock;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    /** 이 시각 전에는 재연결하지 않는다. null이면 바로 시도할 수 있다. */
    private final AtomicReference<Instant> nextAttemptAt = new AtomicReference<>();

    ReconnectBackoff(Clock clock) {
        this.clock = clock;
    }

    /** 연결 실패 한 번 — 연속 실패 수를 올리고 다음 시도 시각을 미룬다. */
    void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        nextAttemptAt.set(clock.instant().plusSeconds(delaySecondsFor(failures)));
    }

    /** 연결 성공·장외 대기 진입 — 처음(즉시 시도, 다음 실패는 10초부터)으로 돌린다. */
    void reset() {
        consecutiveFailures.set(0);
        nextAttemptAt.set(null);
    }

    /** 지금 재연결을 시도해도 되는가. */
    boolean readyToAttempt() {
        Instant notBefore = nextAttemptAt.get();
        return notBefore == null || !clock.instant().isBefore(notBefore);
    }

    /** 다음 시도 시각(로그용). 없으면 null. */
    Instant nextAttemptAt() {
        return nextAttemptAt.get();
    }

    int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    /** 지금까지 실패 수 기준의 대기 간격(초). */
    long currentDelaySeconds() {
        return delaySecondsFor(consecutiveFailures.get());
    }

    /** 이번 실패는 스택트레이스까지 남길 만한가 — 같은 예외가 반복되면 스택은 정보가 아니라 소음이다. */
    boolean logStackTrace() {
        return consecutiveFailures.get() <= STACKTRACE_UNTIL;
    }

    private static long delaySecondsFor(int failures) {
        if (failures <= 0) {
            return 0;
        }
        return Math.min(MAX_SECONDS, INITIAL_SECONDS * (1L << Math.min(failures - 1, 5)));
    }
}

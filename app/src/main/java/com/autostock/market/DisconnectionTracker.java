package com.autostock.market;

import com.autostock.common.event.MarketDataStale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WS 장시간 단절 감지 — 연속 단절이 {@code autostock.ws.stale-after}(기본 180초)를 넘으면 {@link MarketDataStale}을
 * 단절 구간마다 한 번 발행한다. risk 모듈이 이를 받아 킬스위치를 켠다("끊긴 걸 알면서 계속 매매하는" 사고 방지).
 * {@link KiwoomWebSocketClient}에서 추출(B3, aiDoc/large-classes.md).
 */
final class DisconnectionTracker {

    private static final Logger log = LoggerFactory.getLogger(DisconnectionTracker.class);

    private final Clock clock;
    private final long staleAfterSeconds;
    private final ApplicationEventPublisher publisher;

    /** 단절이 시작된 것으로 판단한 시각. null이면 연결 정상(또는 아직 단절 관측 전). */
    private final AtomicReference<Instant> disconnectedSince = new AtomicReference<>();
    /** 이번 단절 구간에서 이미 발행했는가 — 중복 발행 방지(연결 회복 시 리셋). */
    private final AtomicBoolean staleEventFired = new AtomicBoolean(false);

    DisconnectionTracker(Clock clock, long staleAfterSeconds, ApplicationEventPublisher publisher) {
        this.clock = clock;
        this.staleAfterSeconds = staleAfterSeconds;
        this.publisher = publisher;
    }

    /**
     * watchdog 틱마다 관측한 연결 상태를 반영한다. 끊겨 있으면 첫 관측 시각부터 경과를 재고, 임계치를 넘으면 한 번 발행한다.
     *
     * @param connected 이번 틱에서 관측한 연결 상태(true=정상)
     */
    void observe(boolean connected) {
        if (connected) {
            reset();
            return;
        }
        // 이번이 단절의 첫 관측이면 지금을 시작 시각으로, 이미 있으면 그대로(단절 지속 중).
        Instant since = disconnectedSince.updateAndGet(existing -> existing != null ? existing : clock.instant());
        long elapsedSeconds = Duration.between(since, clock.instant()).getSeconds();
        if (elapsedSeconds >= staleAfterSeconds && staleEventFired.compareAndSet(false, true)) {
            log.error("WS 장시간 단절({}초) — MarketDataStale 발행, risk 모듈이 킬스위치를 켤 것이다", elapsedSeconds);
            publisher.publishEvent(new MarketDataStale(since, elapsedSeconds));
        }
    }

    /**
     * 단절 구간을 끝낸다 — 연결 회복, 장외 대기 진입 때. 대기 시간을 단절로 세면 깨어나는 순간 임계치를 넘긴 것으로
     * 판정돼 킬스위치가 켜진다(KiwoomWebSocketClient "장외 대기" 설명).
     */
    void reset() {
        disconnectedSince.set(null);
        staleEventFired.set(false);
    }
}

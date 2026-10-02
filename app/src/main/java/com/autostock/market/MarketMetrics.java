package com.autostock.market;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * market 모듈 게이지 (실행 계획 1.7, aiDoc/observability.md).
 *
 * <p>{@code market.ws.last_message_age.seconds} — WS로 마지막 메시지(PING 포함, 서버가 약 10초마다 보냄)를 받은 뒤 경과 초.
 * 장중에 수십 초를 넘으면 연결이 죽었거나 수신 스레드가 막힌 것이다. 받은 적이 없으면 NaN.
 */
@Component
class MarketMetrics implements MeterBinder {

    private final KiwoomWebSocketClient webSocket;
    private final Clock clock;

    MarketMetrics(KiwoomWebSocketClient webSocket, Clock clock) {
        this.webSocket = webSocket;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("market.ws.last_message_age.seconds", this, MarketMetrics::lastMessageAgeSeconds)
                .description("WS 마지막 메시지(PING 포함) 뒤 경과 초 — 받은 적 없으면 NaN")
                .strongReference(true)
                .register(registry);
    }

    double lastMessageAgeSeconds() {
        return webSocket.lastMessageAt()
                .map(at -> (double) Duration.between(at, clock.instant()).toSeconds())
                .orElse(Double.NaN);
    }
}

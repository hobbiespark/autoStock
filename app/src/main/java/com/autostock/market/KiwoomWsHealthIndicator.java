package com.autostock.market;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * 헬스 {@code kiwoomWs} — 시세·체결통보 WebSocket (실행 계획 1.7, aiDoc/observability.md).
 *
 * <ul>
 *   <li>장중 세션(ACTIVE)인데 연결이 없거나 LOGIN 전이면 <b>DOWN</b> — 시세·체결통보를 못 받는 상태.</li>
 *   <li>장외 대기(STANDBY)면 연결이 없는 것이 정상이라 <b>UP</b>(session=STANDBY).</li>
 *   <li>WS를 끈 환경({@code autostock.ws.enabled=false})은 <b>UP</b>(enabled=false).</li>
 * </ul>
 * 세부: connected, loggedIn, 마지막 메시지(PING 포함) 뒤 경과 초.
 */
@Component
class KiwoomWsHealthIndicator implements HealthIndicator {

    private final KiwoomWebSocketClient webSocket;
    private final MarketSessionService marketSession;
    private final Clock clock;

    KiwoomWsHealthIndicator(KiwoomWebSocketClient webSocket, MarketSessionService marketSession, Clock clock) {
        this.webSocket = webSocket;
        this.marketSession = marketSession;
        this.clock = clock;
    }

    @Override
    public Health health() {
        if (!webSocket.isEnabled()) {
            return Health.up().withDetail("enabled", false).build();
        }
        boolean active = marketSession.isActive();
        boolean connected = webSocket.isConnected();
        boolean loggedIn = webSocket.isLoggedIn();
        Health.Builder builder = active && !(connected && loggedIn) ? Health.down() : Health.up();
        builder.withDetail("session", active ? "ACTIVE" : "STANDBY")
                .withDetail("connected", connected)
                .withDetail("loggedIn", loggedIn);
        webSocket.lastMessageAt().ifPresent(at ->
                builder.withDetail("lastMessageAgeSeconds", Duration.between(at, clock.instant()).toSeconds()));
        return builder.build();
    }
}

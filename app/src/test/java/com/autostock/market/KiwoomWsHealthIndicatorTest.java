package com.autostock.market;

import com.autostock.kiwoom.KiwoomProperties;
import com.autostock.kiwoom.TokenManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 헬스 kiwoomWs·게이지 market.ws.last_message_age.seconds(실행 계획 1.7) — 실제 WS 클라이언트 객체에 가짜 세션을 붙여
 * 상태 전이(꺼짐 → 장외 → 장중 미연결 → 연결·LOGIN 전 → LOGIN 후)를 확인한다.
 */
class KiwoomWsHealthIndicatorTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-06T00:00:00Z"));
    private final MarketSessionService marketSession = mock(MarketSessionService.class);
    private final TokenManager tokenManager = mock(TokenManager.class);
    private KiwoomWebSocketClient client;
    private WebSocketSession session;

    @BeforeEach
    void setUp() {
        when(tokenManager.accessToken()).thenReturn("test-token");
        when(marketSession.isActive()).thenReturn(true);
        client = client(true);
        session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
    }

    private KiwoomWebSocketClient client(boolean enabled) {
        return new KiwoomWebSocketClient(mock(KiwoomProperties.class), tokenManager, event -> { }, new ObjectMapper(),
                enabled, clock, 180L, marketSession);
    }

    private Health health(KiwoomWebSocketClient target) {
        return new KiwoomWsHealthIndicator(target, marketSession, clock).health();
    }

    @Test
    void WS를_끈_환경은_UP이다() {
        Health health = health(client(false));

        assertEquals(Status.UP, health.getStatus());
        assertEquals(false, health.getDetails().get("enabled"));
    }

    @Test
    void 장외_대기면_연결이_없어도_UP이다() {
        when(marketSession.isActive()).thenReturn(false);

        Health health = health(client);

        assertEquals(Status.UP, health.getStatus());
        assertEquals("STANDBY", health.getDetails().get("session"));
    }

    @Test
    void 장중인데_연결이_없거나_LOGIN_전이면_DOWN이고_LOGIN_뒤에는_UP이다() throws Exception {
        assertEquals(Status.DOWN, health(client).getStatus(), "연결 없음");

        client.afterConnectionEstablished(session);
        assertEquals(Status.DOWN, health(client).getStatus(), "연결됐지만 LOGIN 응답 전");

        client.handleTextMessage(session, new TextMessage("{\"trnm\":\"LOGIN\",\"return_code\":0,\"sor_yn\":\"N\"}"));
        clock.advance(Duration.ofSeconds(12));
        Health up = health(client);
        assertEquals(Status.UP, up.getStatus());
        assertEquals(true, up.getDetails().get("connected"));
        assertEquals(true, up.getDetails().get("loggedIn"));
        assertEquals(12L, up.getDetails().get("lastMessageAgeSeconds"));

        when(session.isOpen()).thenReturn(false);   // 끊김 — 재연결 전
        assertEquals(Status.DOWN, health(client).getStatus());
    }

    @Test
    void 마지막_메시지_뒤_경과_초를_게이지로_낸다() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new MarketMetrics(client, clock).bindTo(registry);
        assertTrue(Double.isNaN(registry.get("market.ws.last_message_age.seconds").gauge().value()), "받은 적 없음");

        client.handleTextMessage(session, new TextMessage("{\"trnm\":\"PING\"}"));
        clock.advance(Duration.ofSeconds(30));

        assertEquals(30.0, registry.get("market.ws.last_message_age.seconds").gauge().value());
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
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

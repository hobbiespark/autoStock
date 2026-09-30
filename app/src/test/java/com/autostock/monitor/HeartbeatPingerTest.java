package com.autostock.monitor;

import com.autostock.market.MarketSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 외부 heartbeat 핑(Phase 0.7) — 장중(ACTIVE)·URL 있을 때만 GET, 실패는 삼킨다. 실제 네트워크 대신 ExchangeFunction을 끼운다.
 */
class HeartbeatPingerTest {

    private static final String URL = "https://hc-ping.example/1f2e3d4c";

    private final List<String> requests = new ArrayList<>();
    private final MarketSessionService session = mock(MarketSessionService.class);

    private WebClient.Builder builder(HttpStatus status) {
        return WebClient.builder().exchangeFunction(request -> {
            requests.add(request.method() + " " + request.url());
            return Mono.just(ClientResponse.create(status).build());
        });
    }

    @Test
    void 장중이고_URL이_있으면_GET으로_핑한다() {
        when(session.isActive()).thenReturn(true);

        new HeartbeatPinger(builder(HttpStatus.OK), URL, session).ping();

        assertEquals(List.of(HttpMethod.GET + " " + URL), requests);
    }

    @Test
    void 장외_대기중에는_핑하지_않는다() {
        when(session.isActive()).thenReturn(false);

        new HeartbeatPinger(builder(HttpStatus.OK), URL, session).ping();

        assertTrue(requests.isEmpty());
    }

    @Test
    void URL이_비면_아무것도_하지_않는다() {
        when(session.isActive()).thenReturn(true);

        new HeartbeatPinger(builder(HttpStatus.OK), "  ", session).ping();
        new HeartbeatPinger(builder(HttpStatus.OK), null, session).ping();

        assertTrue(requests.isEmpty());
    }

    @Test
    void 핑이_실패해도_예외를_던지지_않는다() {
        when(session.isActive()).thenReturn(true);

        new HeartbeatPinger(builder(HttpStatus.SERVICE_UNAVAILABLE), URL, session).ping(); // 예외 없이 끝나야 한다

        assertEquals(1, requests.size());
    }
}

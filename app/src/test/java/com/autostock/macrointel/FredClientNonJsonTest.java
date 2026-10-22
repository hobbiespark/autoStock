package com.autostock.macrointel;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FRED가 JSON이 아닌 본문(200 OK text/html)을 돌려줄 때(2026-10-22 실측: 06:19~13:05 매시 실패, 14:05 회복) —
 * 한 번 더 묻고, 그래도 아니면 빈 결과(WARN 한 줄). 예외를 밖으로 던지지 않는다.
 */
class FredClientNonJsonTest {

    private static final MacroIntelProperties PROPERTIES =
            new MacroIntelProperties(true, "test-fred-key", "", 25.0, 35.0, 1450.0, 7);

    /** 본문을 차례로 돌려주는 대역 — 기다리지 않는다. */
    private static final class Stub extends FredClient {
        private final Deque<String> bodies;
        int calls;
        int pauses;

        Stub(String... bodies) {
            super(WebClient.builder(), PROPERTIES);
            this.bodies = new ArrayDeque<>(List.of(bodies));
        }

        @Override
        protected String fetchBody(String seriesId) {
            calls++;
            return bodies.isEmpty() ? "" : bodies.poll();
        }

        @Override
        protected void pause(Duration delay) {
            pauses++;
        }
    }

    private static final String HTML = "<html><head><title>Service Unavailable</title></head><body>…</body></html>";

    @Test
    void HTML이_이어지면_한_번_더_묻고_빈_결과를_돌려준다() {
        Stub client = new Stub(HTML, HTML);

        assertTrue(client.fetchLatest(FredClient.SERIES_VIX).isEmpty());
        assertEquals(2, client.calls);
        assertEquals(1, client.pauses);
    }

    @Test
    void HTML_뒤에_JSON이_오면_읽는다() {
        Stub client = new Stub(HTML, "{\"observations\":[{\"date\":\"2026-10-21\",\"value\":\"17.31\"}]}");

        Optional<FredClient.Observation> result = client.fetchLatest(FredClient.SERIES_VIX);

        assertTrue(result.isPresent());
        assertEquals(new BigDecimal("17.31"), result.get().value());
    }

    @Test
    void 로그에는_HTML_제목이나_본문_앞부분을_남긴다() {
        assertEquals("HTML 제목 \"Service Unavailable\"",
                FredClient.describe("<html><title>  Service\n  Unavailable </title></html>"));
        assertEquals("본문 앞부분 \"Rate limit exceeded\"", FredClient.describe("Rate limit   exceeded"));
        assertEquals("빈 본문", FredClient.describe(""));
    }
}

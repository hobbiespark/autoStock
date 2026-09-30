package com.autostock.kiwoom;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 토큰 거부(8005) 시 재발급 후 1회 재시도 검증 — 실제 서버 대신 WebClient의 ExchangeFunction을 바꿔 끼운다.
 * 실측(2026-09-30): 절전 복귀 직후 만료 전 토큰이 {@code 인증에 실패했습니다[8005:Token이 유효하지 않습니다]}로 거부됐다.
 */
class KiwoomRestClientTest {

    private static final KiwoomProperties PROPERTIES =
            new KiwoomProperties("https://mock.kiwoom.test", "wss://mock.kiwoom.test/ws", "test-key", "test-secret");
    private static final String REJECTED =
            "{\"return_code\":3,\"return_msg\":\"인증에 실패했습니다[8005:Token이 유효하지 않습니다]\"}";
    private static final String OK = "{\"return_code\":0,\"return_msg\":\"정상\"}";

    private final List<String> sentTokens = new ArrayList<>();

    @Test
    void 토큰이_거부되면_재발급한_토큰으로_한번_더_보낸다() {
        IssuingTokenManager tokens = new IssuingTokenManager();
        KiwoomRestClient client = client(tokens, REJECTED, OK);

        Map<String, Object> response = client.call(TrId.ACCOUNT_BALANCE, "/api/dostk/acnt", Map.of());

        assertEquals(0, ((Number) response.get("return_code")).intValue());
        assertEquals(List.of("Bearer token-1", "Bearer token-2"), sentTokens);
        assertEquals(2, tokens.issued.get());
    }

    @Test
    void 재발급_후에도_거부되면_더_시도하지_않고_실패한다() {
        IssuingTokenManager tokens = new IssuingTokenManager();
        KiwoomRestClient client = client(tokens, REJECTED, REJECTED, OK);

        assertThrows(KiwoomApiException.class, () -> client.call(TrId.ACCOUNT_BALANCE, "/api/dostk/acnt", Map.of()));
        assertEquals(2, sentTokens.size(), "토큰 거부 재시도는 한 번뿐이다");
    }

    @Test
    void 다른_논리_오류는_재시도하지_않는다() {
        IssuingTokenManager tokens = new IssuingTokenManager();
        KiwoomRestClient client = client(tokens,
                "{\"return_code\":2,\"return_msg\":\"[2000:입력값 오류]\"}", OK);

        assertThrows(KiwoomApiException.class, () -> client.call(TrId.ACCOUNT_BALANCE, "/api/dostk/acnt", Map.of()));
        assertEquals(1, sentTokens.size());
        assertEquals(1, tokens.issued.get());
    }

    /** 응답 본문을 순서대로 돌려주는 가짜 서버를 끼운 클라이언트. */
    private KiwoomRestClient client(TokenManager tokens, String... bodies) {
        AtomicInteger index = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            sentTokens.add(request.headers().getFirst("authorization"));
            String body = bodies[Math.min(index.getAndIncrement(), bodies.length - 1)];
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .build());
        });
        return new KiwoomRestClient(builder, PROPERTIES, tokens, new TrRateLimiter(), new SimpleMeterRegistry());
    }

    /**
     * 발급할 때마다 새 토큰(token-1, token-2, …)을 주는 TokenManager. 시계는 읽을 때마다 5분씩 흘러
     * 발급 직후 거부 보호({@link TokenManager#MIN_AGE_TO_INVALIDATE})에 걸리지 않게 한다.
     */
    private static final class IssuingTokenManager extends TokenManager {
        final AtomicInteger issued = new AtomicInteger();

        IssuingTokenManager() {
            super(WebClient.builder(), PROPERTIES, new SteppingClock());
        }

        @Override
        protected Map<String, Object> callApi() {
            return Map.of("token", "token-" + issued.incrementAndGet(), "return_code", 0, "expires_dt", "20991231235959");
        }
    }

    /** instant()를 부를 때마다 5분씩 앞으로 가는 시계. */
    private static final class SteppingClock extends Clock {
        private Instant now = Instant.parse("2026-09-30T00:00:00Z");

        @Override
        public synchronized Instant instant() {
            now = now.plus(Duration.ofMinutes(5));
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

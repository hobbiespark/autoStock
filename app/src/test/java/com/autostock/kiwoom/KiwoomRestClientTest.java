package com.autostock.kiwoom;

import com.autostock.common.event.BrokerAuthFailure;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    /** 클라이언트·토큰 관리자가 발행한 이벤트(BrokerAuthFailure). */
    private final List<Object> published = new ArrayList<>();

    private static String logicError(int returnCode, String message) {
        return "{\"return_code\":" + returnCode + ",\"return_msg\":\"" + message + "\"}";
    }

    private List<BrokerAuthFailure> authFailures() {
        return published.stream().filter(BrokerAuthFailure.class::isInstance).map(BrokerAuthFailure.class::cast).toList();
    }

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

    // ---- Phase 0.6: 오류코드 분류 (aiDoc/kiwoom-error-codes.md) ----

    @Test
    void 전체_유량_1701과_그룹_유량_1702도_재시도한다() {
        IssuingTokenManager tokens = new IssuingTokenManager();
        KiwoomRestClient client = client(tokens,
                logicError(5, "허용된 전체 요청 개수를 초과하였습니다[1701:총유량=20]"),
                logicError(5, "허용된 그룹 요청 개수를 초과하였습니다[1702:총유량=10, API_ID=ka10081]"),
                OK);

        Map<String, Object> response = client.call(TrId.ACCOUNT_BALANCE, "/api/dostk/acnt", Map.of());

        assertEquals(0, ((Number) response.get("return_code")).intValue());
        assertEquals(3, sentTokens.size());
        assertTrue(published.isEmpty());
    }

    @Test
    void IP_불일치_8010은_재발급한_토큰으로_한번_더_보낸다() {
        IssuingTokenManager tokens = new IssuingTokenManager();
        KiwoomRestClient client = client(tokens,
                logicError(3, "인증에 실패했습니다[8010:Token을 발급받은 IP와 서비스를 요청한 IP가 동일하지 않습니다]"), OK);

        client.call(TrId.ACCOUNT_BALANCE, "/api/dostk/acnt", Map.of());

        assertEquals(List.of("Bearer token-1", "Bearer token-2"), sentTokens);
        assertTrue(published.isEmpty(), "재발급으로 풀렸으면 알리지 않는다");
    }

    @Test
    void 재발급_뒤에도_8010이면_인증_실패를_한번_알린다() {
        IssuingTokenManager tokens = new IssuingTokenManager();
        String ipMismatch = logicError(3, "인증에 실패했습니다[8010:Token을 발급받은 IP와 서비스를 요청한 IP가 동일하지 않습니다]");
        KiwoomRestClient client = client(tokens, ipMismatch, ipMismatch);

        assertThrows(KiwoomApiException.class, () -> client.call(TrId.ACCOUNT_BALANCE, "/api/dostk/acnt", Map.of()));

        assertEquals(2, sentTokens.size());
        assertEquals(1, authFailures().size());
        assertEquals("8010", authFailures().get(0).code());
        assertEquals("mock.kiwoom.test", authFailures().get(0).host());
    }

    @Test
    void 단말기_인증_실패_8040은_재시도하지_않고_알린다() {
        IssuingTokenManager tokens = new IssuingTokenManager();
        KiwoomRestClient client = client(tokens, logicError(3, "인증에 실패했습니다[8040:단말기 인증에 실패했습니다]"), OK);

        assertThrows(KiwoomApiException.class, () -> client.call(TrId.ACCOUNT_BALANCE, "/api/dostk/acnt", Map.of()));

        assertEquals(1, sentTokens.size());
        assertEquals("8040", authFailures().get(0).code());
        assertTrue(authFailures().get(0).message().contains("[8040:"));
    }

    @Test
    void 인증_계열이_아닌_오류는_알리지_않는다() {
        IssuingTokenManager tokens = new IssuingTokenManager();
        KiwoomRestClient client = client(tokens, logicError(2, "[2000:입력값 오류]"));

        assertThrows(KiwoomApiException.class, () -> client.call(TrId.ACCOUNT_BALANCE, "/api/dostk/acnt", Map.of()));

        assertTrue(published.isEmpty());
    }

    @Test
    void 토큰_발급이_인증_코드로_실패하면_한번만_알리고_원인_코드를_남긴다() {
        // 발급 실패는 TokenManager가 알린다 — REST 호출을 거쳐 올라와도 클라이언트가 다시 알리지 않는다(중복 방지)
        TokenManager failing = new TokenManager(WebClient.builder(), PROPERTIES, published::add, new SteppingClock()) {
            @Override
            protected Map<String, Object> callApi() {
                return Map.of("return_code", 3, "return_msg", "인증에 실패했습니다[8001:App Key와 Secret Key 검증에 실패했습니다]");
            }
        };
        KiwoomRestClient client = client(failing, OK);

        KiwoomApiException e = assertThrows(KiwoomApiException.class,
                () -> client.call(TrId.ACCOUNT_BALANCE, "/api/dostk/acnt", Map.of()));

        assertInstanceOf(KiwoomTokenIssueException.class, e);
        assertTrue(e.getMessage().contains("[8001:"), "예전에는 실패 응답에 토큰이 없어 '응답 없음'으로 원인 코드를 잃었다");
        assertEquals(1, authFailures().size());
        assertEquals("8001", authFailures().get(0).code());
        assertTrue(sentTokens.isEmpty(), "토큰이 없으면 요청을 보내지 않는다");
    }

    @Test
    void 오류코드는_대괄호_안_숫자_네자리만_본다() {
        assertTrue(KiwoomErrorCodes.find("키움 API 논리 오류 [ka10081] [1700:초과]", KiwoomErrorCodes.RATE_LIMIT).isPresent());
        assertTrue(KiwoomErrorCodes.find("키움 API 논리 오류 [ka10081] 정상", KiwoomErrorCodes.RATE_LIMIT).isEmpty());
        assertTrue(KiwoomErrorCodes.find("[17001:다른 코드]", KiwoomErrorCodes.RATE_LIMIT).isEmpty());
        assertEquals("8103", KiwoomErrorCodes.find("x[8103]y", KiwoomErrorCodes.AUTH_FAILURE).orElseThrow());
    }

    @Test
    void 연속_조회는_다음_키를_헤더로_보내고_응답의_다음_키를_돌려준다() {
        // 키움 REST 규약(scripts/probe_ka10080_depth.ps1 실측): 요청 헤더 cont-yn·next-key, 응답 헤더 cont-yn=Y면 next-key
        List<HttpHeaders> sent = new ArrayList<>();
        AtomicInteger index = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            sent.add(request.headers());
            boolean last = index.getAndIncrement() > 0;
            ClientResponse.Builder response = ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header("cont-yn", last ? "N" : "Y")
                    .body(OK);
            if (!last) {
                response.header("next-key", "20260930090100-page2");
            }
            return Mono.just(response.build());
        });
        KiwoomRestClient client = new KiwoomRestClient(builder, PROPERTIES, new IssuingTokenManager(), new TrRateLimiter(),
                new SimpleMeterRegistry(), published::add, Clock.systemUTC());

        KiwoomRestClient.Page first = client.callPage(TrId.MINUTE_CHART, "/api/dostk/chart", Map.of(), null);
        KiwoomRestClient.Page second = client.callPage(TrId.MINUTE_CHART, "/api/dostk/chart", Map.of(), first.nextKey());

        assertEquals("20260930090100-page2", first.nextKey());
        assertTrue(first.hasNext());
        assertNull(second.nextKey());
        assertEquals(0, ((Number) second.body().get("return_code")).intValue());
        assertNull(sent.get(0).getFirst("cont-yn"));   // 첫 페이지는 지금까지의 단건 호출과 같다
        assertEquals("Y", sent.get(1).getFirst("cont-yn"));
        assertEquals("20260930090100-page2", sent.get(1).getFirst("next-key"));
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
        return new KiwoomRestClient(builder, PROPERTIES, tokens, new TrRateLimiter(), new SimpleMeterRegistry(),
                published::add, Clock.systemUTC());
    }

    /**
     * 발급할 때마다 새 토큰(token-1, token-2, …)을 주는 TokenManager. 시계는 읽을 때마다 5분씩 흘러
     * 발급 직후 거부 보호({@link TokenManager#MIN_AGE_TO_INVALIDATE})에 걸리지 않게 한다.
     */
    private static final class IssuingTokenManager extends TokenManager {
        final AtomicInteger issued = new AtomicInteger();

        IssuingTokenManager() {
            super(WebClient.builder(), PROPERTIES, event -> { }, new SteppingClock());
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

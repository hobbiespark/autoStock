package com.autostock.monitor;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TelegramNotifier 단위테스트 — 실제 텔레그램 서버는 절대 호출하지 않는다(mock WebClient).
 */
class TelegramNotifierTest {

    private final TelegramProperties properties = new TelegramProperties(true, "test-token", "123456");

    /** 2026-10-12 18:05:20 KST — PC가 대기에서 깨어난 시각(실측). */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-12T09:05:20Z"), ZoneOffset.UTC);

    /** 예약된 재전송 — 기다리지 않고 테스트가 직접 돌린다. */
    private final List<Duration> resendDelays = new ArrayList<>();
    private final List<Runnable> resendTasks = new ArrayList<>();
    private final TelegramNotifier.Resender resender = (task, delay) -> {
        resendDelays.add(delay);
        resendTasks.add(task);
    };

    /** WebClient.Builder.baseUrl(...).build()가 주어진 mock WebClient를 돌려주도록 배선한다. */
    private WebClient.Builder builderReturning(WebClient webClient) {
        WebClient.Builder builder = mock(WebClient.Builder.class);
        when(builder.baseUrl(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(webClient);
        return builder;
    }

    @Test
    void 정상_응답이면_예외없이_전송한다() {
        WebClient webClient = mock(WebClient.class, RETURNS_DEEP_STUBS);
        when(webClient.post().uri(anyString(), any(Object[].class)).bodyValue(any())
                .retrieve().toBodilessEntity())
                .thenReturn(Mono.just(ResponseEntity.ok().build()));

        TelegramNotifier notifier = new TelegramNotifier(builderReturning(webClient), properties, CLOCK);

        assertDoesNotThrow(() -> notifier.notify(NoticeLevel.INFO, "체결 완료"));
        // when(...) 자체가 스텁 설정 과정에서 post()를 한 번 호출하므로(deep stub 특성),
        // 정확한 횟수(1회) 대신 "최소 한 번은 실제로 호출됐다"만 확인한다.
        verify(webClient, atLeastOnce()).post();
    }

    @Test
    void 발송_실패해도_예외를_삼킨다() {
        // 알림 실패가 매매 흐름을 막으면 안 되므로 — WebClient가 어떤 예외를 던지든
        // TelegramNotifier.notify()는 절대 예외를 전파하지 않아야 한다.
        WebClient webClient = mock(WebClient.class, RETURNS_DEEP_STUBS);
        when(webClient.post().uri(anyString(), any(Object[].class)).bodyValue(any())
                .retrieve().toBodilessEntity().block())
                .thenThrow(new RuntimeException("네트워크 오류(모의)"));

        TelegramNotifier notifier = new TelegramNotifier(builderReturning(webClient), properties, CLOCK);

        assertDoesNotThrow(() -> notifier.notify(NoticeLevel.CRITICAL, "킬스위치 작동: 테스트"));
    }

    // ── 발송 제한(429) — 2026-10-01, aiDoc/alert-digest.md ──────────────────────────────

    /** 텔레그램 429 응답 — 본문 parameters.retry_after(초)에 대기 시간이 온다. */
    private static WebClientResponseException tooManyRequests(int retryAfterSeconds) {
        String body = ("{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests: retry after %d\","
                + "\"parameters\":{\"retry_after\":%d}}").formatted(retryAfterSeconds, retryAfterSeconds);
        return WebClientResponseException.create(HttpStatusCode.valueOf(429), "Too Many Requests",
                HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8, null);
    }

    /** n번째 호출(1부터)에 무엇을 돌려줄지 정한 WebClient — 실제 호출 횟수를 calls에 센다. */
    private static WebClient webClientAnswering(AtomicInteger calls, java.util.function.IntFunction<Mono<ResponseEntity<Void>>> answer) {
        WebClient webClient = mock(WebClient.class, RETURNS_DEEP_STUBS);
        when(webClient.post().uri(anyString(), any(Object[].class)).bodyValue(any()).retrieve().toBodilessEntity())
                .thenAnswer(inv -> answer.apply(calls.incrementAndGet()));
        return webClient;
    }

    @Test
    void 발송_제한_429면_retry_after만큼_기다렸다가_한_번_재시도한다() {
        AtomicInteger calls = new AtomicInteger();
        WebClient webClient = webClientAnswering(calls,
                n -> n == 1 ? Mono.error(tooManyRequests(3)) : Mono.just(ResponseEntity.ok().build()));
        List<Duration> waits = new ArrayList<>();

        TelegramNotifier notifier = new TelegramNotifier(builderReturning(webClient), properties, waits::add, CLOCK, resender);
        notifier.notify(NoticeLevel.INFO, "공시 블랙리스트 요약");

        assertEquals(2, calls.get());
        assertEquals(List.of(Duration.ofSeconds(3)), waits);
    }

    @Test
    void retry_after가_상한보다_길면_기다리지_않고_버린다() {
        AtomicInteger calls = new AtomicInteger();
        WebClient webClient = webClientAnswering(calls, n -> Mono.error(tooManyRequests(30)));
        List<Duration> waits = new ArrayList<>();

        TelegramNotifier notifier = new TelegramNotifier(builderReturning(webClient), properties, waits::add, CLOCK, resender);

        assertDoesNotThrow(() -> notifier.notify(NoticeLevel.INFO, "요약"));
        assertEquals(1, calls.get());
        assertTrue(waits.isEmpty());
    }

    @Test
    void 재시도도_실패하면_예외를_삼키고_더_재시도하지_않는다() {
        AtomicInteger calls = new AtomicInteger();
        WebClient webClient = webClientAnswering(calls, n -> Mono.error(tooManyRequests(1)));
        List<Duration> waits = new ArrayList<>();

        TelegramNotifier notifier = new TelegramNotifier(builderReturning(webClient), properties, waits::add, CLOCK, resender);

        assertDoesNotThrow(() -> notifier.notify(NoticeLevel.CRITICAL, "킬스위치 작동"));
        assertEquals(2, calls.get());
        assertEquals(List.of(Duration.ofSeconds(1)), waits);
    }

    @Test
    void 다른_HTTP_오류는_재시도하지_않는다() {
        AtomicInteger calls = new AtomicInteger();
        WebClient webClient = webClientAnswering(calls, n -> Mono.error(WebClientResponseException.create(
                HttpStatusCode.valueOf(400), "Bad Request", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8, null)));
        List<Duration> waits = new ArrayList<>();

        TelegramNotifier notifier = new TelegramNotifier(builderReturning(webClient), properties, waits::add, CLOCK, resender);

        assertDoesNotThrow(() -> notifier.notify(NoticeLevel.INFO, "잘못된 chat_id"));
        assertEquals(1, calls.get());
        assertTrue(waits.isEmpty());
        assertTrue(resendTasks.isEmpty(), "응답을 받은 4xx는 다시 보내도 같다 — 재전송하지 않는다");
    }

    @Test
    void 대기_시간은_본문_다음_헤더_둘_다_없으면_1초다() {
        assertEquals(Duration.ofSeconds(7), TelegramNotifier.retryAfter(tooManyRequests(7)));

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "4");
        assertEquals(Duration.ofSeconds(4), TelegramNotifier.retryAfter(WebClientResponseException.create(
                HttpStatusCode.valueOf(429), "Too Many Requests", headers, new byte[0], StandardCharsets.UTF_8, null)));

        assertEquals(Duration.ofSeconds(1), TelegramNotifier.retryAfter(WebClientResponseException.create(
                HttpStatusCode.valueOf(429), "Too Many Requests", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8, null)));
    }
    // ── 요청 지표에 토큰이 남지 않는다(2026-10-06 로그 점검) ─────────────────────────────

    @Test
    void 요청_지표의_uri_태그에_봇_토큰이_남지_않는다() {
        // 실제 WebClient + 관측(운영과 같은 http.client.requests 지표). 네트워크는 쓰지 않는다(교환 함수 대역).
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        List<String> paths = new ArrayList<>();
        WebClient.Builder builder = WebClient.builder()
                .observationRegistry(observations)
                .exchangeFunction(request -> {
                    paths.add(request.url().getRawPath());
                    return Mono.just(ClientResponse.create(HttpStatus.OK).build());
                });

        new TelegramNotifier(builder, properties, CLOCK).notify(NoticeLevel.INFO, "지표 확인");

        Timer requests = meters.find("http.client.requests").timer();
        assertNotNull(requests, "요청 지표가 기록돼야 한다");
        String uri = requests.getId().getTag("uri");
        assertTrue(uri.contains("{token}"), "uri 태그는 템플릿이어야 한다: " + uri);
        assertFalse(meters.getMeters().stream().map(Meter::getId).flatMap(id -> id.getTags().stream())
                .anyMatch(tag -> tag.getValue().contains("test-token")), "어느 지표 태그에도 토큰이 없어야 한다");
        // 실제 요청 경로에는 토큰이 들어간다(발송은 그대로)
        assertEquals(List.of("/bottest-token/sendMessage"), paths);
    }

    // ── 망 오류 지연 재전송(2026-10-12 — 대기에서 깨어난 직후 경고가 전부 사라짐) ───────────────────

    /** 응답을 받지 못한 실패 — DNS·연결·타임아웃. */
    private static WebClientRequestException networkFailure() {
        return new WebClientRequestException(new UnknownHostException("api.telegram.org"), HttpMethod.POST,
                URI.create("https://api.telegram.org/sendMessage"), HttpHeaders.EMPTY);
    }

    /** 실제 WebClient + 교환 함수 대역 — 보낸 본문을 모으고, n번째 호출(1부터)의 결과를 정한다. */
    private static WebClient.Builder exchanging(List<String> bodies, java.util.function.IntFunction<Mono<ClientResponse>> answer) {
        AtomicInteger calls = new AtomicInteger();
        return WebClient.builder().exchangeFunction(request -> {
            bodies.add(bodyOf(request));
            return answer.apply(calls.incrementAndGet());
        });
    }

    private static String bodyOf(ClientRequest request) {
        MockClientHttpRequest written = new MockClientHttpRequest(request.method(), request.url());
        request.body().insert(written, new BodyInserter.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return ExchangeStrategies.withDefaults().messageWriters();
            }

            @Override
            public Optional<ServerHttpRequest> serverRequest() {
                return Optional.empty();
            }

            @Override
            public Map<String, Object> hints() {
                return Map.of();
            }
        }).block();
        return written.getBodyAsString().block();
    }

    @Test
    void 망_오류면_30초_뒤_다시_보내고_처음_발생_시각을_붙인다() {
        List<String> bodies = new ArrayList<>();
        WebClient.Builder builder = exchanging(bodies,
                n -> n == 1 ? Mono.error(networkFailure()) : Mono.just(ClientResponse.create(HttpStatus.OK).build()));
        TelegramNotifier notifier = new TelegramNotifier(builder, properties, d -> { }, CLOCK, resender);

        assertDoesNotThrow(() -> notifier.notify(NoticeLevel.WARN, "절전 복귀 감지 — 장중 6시간 45분 멈춤"));

        assertEquals(1, bodies.size());
        assertEquals(List.of(Duration.ofSeconds(30)), resendDelays);
        resendTasks.get(0).run();
        assertEquals(2, bodies.size());
        assertTrue(bodies.get(1).contains("[WARN] (지연 발송 — 18:05:20 발생) 절전 복귀 감지 — 장중 6시간 45분 멈춤"), bodies.get(1));
        assertEquals(1, resendTasks.size(), "보냈으면 더 예약하지 않는다");
    }

    @Test
    void 계속_망_오류면_30초_2분_5분_간격으로_세_번까지만_다시_보낸다() {
        List<String> bodies = new ArrayList<>();
        TelegramNotifier notifier = new TelegramNotifier(exchanging(bodies, n -> Mono.error(networkFailure())),
                properties, d -> { }, CLOCK, resender);

        notifier.notify(NoticeLevel.WARN, "장중 점검 이상");
        for (int i = 0; i < resendTasks.size(); i++) {
            Runnable task = resendTasks.get(i);
            assertDoesNotThrow(task::run);
        }

        assertEquals(List.of(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(5)), resendDelays);
        assertEquals(4, bodies.size(), "처음 1번 + 재전송 3번");
    }

    @Test
    void 서버_오류_5xx도_다시_보낸다() {
        List<String> bodies = new ArrayList<>();
        WebClient.Builder builder = exchanging(bodies, n -> n == 1
                ? Mono.just(ClientResponse.create(HttpStatus.BAD_GATEWAY).build())
                : Mono.just(ClientResponse.create(HttpStatus.OK).build()));
        TelegramNotifier notifier = new TelegramNotifier(builder, properties, d -> { }, CLOCK, resender);

        notifier.notify(NoticeLevel.INFO, "일일 성과 리포트");
        resendTasks.get(0).run();

        assertEquals(2, bodies.size());
        assertEquals(List.of(Duration.ofSeconds(30)), resendDelays);
    }

    @Test
    void 재전송_대기열이_가득하면_그_뒤_알림은_붙들지_않는다() {
        TelegramNotifier notifier = new TelegramNotifier(exchanging(new ArrayList<>(), n -> Mono.error(networkFailure())),
                properties, d -> { }, CLOCK, resender);

        for (int i = 0; i < TelegramNotifier.MAX_PENDING_RESENDS + 3; i++) {
            notifier.notify(NoticeLevel.INFO, "알림 " + i);
        }

        assertEquals(TelegramNotifier.MAX_PENDING_RESENDS, resendTasks.size());
    }

    @Test
    void 다시_보낼_만한_실패는_응답이_없거나_5xx일_때뿐이다() {
        assertTrue(TelegramNotifier.resendable(networkFailure()));
        assertTrue(TelegramNotifier.resendable(WebClientResponseException.create(
                HttpStatusCode.valueOf(503), "Service Unavailable", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8, null)));
        assertFalse(TelegramNotifier.resendable(WebClientResponseException.create(
                HttpStatusCode.valueOf(403), "Forbidden", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8, null)));
        assertFalse(TelegramNotifier.resendable(new IllegalStateException("모르는 실패")));
    }
}

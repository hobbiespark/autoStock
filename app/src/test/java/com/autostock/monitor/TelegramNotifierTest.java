package com.autostock.monitor;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        when(webClient.post().uri(anyString()).bodyValue(any())
                .retrieve().toBodilessEntity())
                .thenReturn(Mono.just(ResponseEntity.ok().build()));

        TelegramNotifier notifier = new TelegramNotifier(builderReturning(webClient), properties);

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
        when(webClient.post().uri(anyString()).bodyValue(any())
                .retrieve().toBodilessEntity().block())
                .thenThrow(new RuntimeException("네트워크 오류(모의)"));

        TelegramNotifier notifier = new TelegramNotifier(builderReturning(webClient), properties);

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
        when(webClient.post().uri(anyString()).bodyValue(any()).retrieve().toBodilessEntity())
                .thenAnswer(inv -> answer.apply(calls.incrementAndGet()));
        return webClient;
    }

    @Test
    void 발송_제한_429면_retry_after만큼_기다렸다가_한_번_재시도한다() {
        AtomicInteger calls = new AtomicInteger();
        WebClient webClient = webClientAnswering(calls,
                n -> n == 1 ? Mono.error(tooManyRequests(3)) : Mono.just(ResponseEntity.ok().build()));
        List<Duration> waits = new ArrayList<>();

        TelegramNotifier notifier = new TelegramNotifier(builderReturning(webClient), properties, waits::add);
        notifier.notify(NoticeLevel.INFO, "공시 블랙리스트 요약");

        assertEquals(2, calls.get());
        assertEquals(List.of(Duration.ofSeconds(3)), waits);
    }

    @Test
    void retry_after가_상한보다_길면_기다리지_않고_버린다() {
        AtomicInteger calls = new AtomicInteger();
        WebClient webClient = webClientAnswering(calls, n -> Mono.error(tooManyRequests(30)));
        List<Duration> waits = new ArrayList<>();

        TelegramNotifier notifier = new TelegramNotifier(builderReturning(webClient), properties, waits::add);

        assertDoesNotThrow(() -> notifier.notify(NoticeLevel.INFO, "요약"));
        assertEquals(1, calls.get());
        assertTrue(waits.isEmpty());
    }

    @Test
    void 재시도도_실패하면_예외를_삼키고_더_재시도하지_않는다() {
        AtomicInteger calls = new AtomicInteger();
        WebClient webClient = webClientAnswering(calls, n -> Mono.error(tooManyRequests(1)));
        List<Duration> waits = new ArrayList<>();

        TelegramNotifier notifier = new TelegramNotifier(builderReturning(webClient), properties, waits::add);

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

        TelegramNotifier notifier = new TelegramNotifier(builderReturning(webClient), properties, waits::add);

        assertDoesNotThrow(() -> notifier.notify(NoticeLevel.INFO, "잘못된 chat_id"));
        assertEquals(1, calls.get());
        assertTrue(waits.isEmpty());
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
}

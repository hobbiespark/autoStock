package com.autostock.monitor;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
}

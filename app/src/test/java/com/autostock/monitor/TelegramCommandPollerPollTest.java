package com.autostock.monitor;

import com.autostock.risk.KillSwitch;
import com.autostock.portfolio.PositionBook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TelegramCommandPoller.poll() 전체 흐름 — getUpdates 응답(mock)을 받아 실제 킬스위치를
 * 조작하는지 검증한다. Bot API 호출은 전부 mock WebClient로 대체(실제 네트워크 없음).
 *
 * <p>여기서 쓰는 getUpdates 응답 JSON 구조는 텔레그램 공식 문서 기반 추정이다
 * (TelegramCommandPoller 클래스 Javadoc의 TODO 실측 참고).
 */
class TelegramCommandPollerPollTest {

    private static final String CHAT_ID = "123456";

    private final List<Object> published = new ArrayList<>();
    private KillSwitch killSwitch;
    private PositionBook positionBook;
    private final List<String> notices = new ArrayList<>();
    private final Notifier fakeNotifier = (level, message) -> notices.add(level + ":" + message);

    @BeforeEach
    void setUp() {
        killSwitch = new KillSwitch(published::add);
        positionBook = new PositionBook();
    }

    private WebClient mockWebClientReturning(Map<String, Object> responseBody) {
        WebClient webClient = mock(WebClient.class, RETURNS_DEEP_STUBS);
        when(webClient.get()
                .uri(any(Function.class))
                .retrieve()
                .bodyToMono(Map.class))
                .thenReturn(Mono.just(responseBody));
        return webClient;
    }

    private WebClient.Builder builderReturning(WebClient webClient) {
        WebClient.Builder builder = mock(WebClient.Builder.class);
        when(builder.baseUrl(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(webClient);
        return builder;
    }

    private TelegramCommandPoller pollerWithUpdate(Map<String, Object> update) {
        Map<String, Object> response = Map.of("ok", true, "result", List.of(update));
        TelegramProperties properties = new TelegramProperties(true, "test-token", CHAT_ID);
        WebClient webClient = mockWebClientReturning(response);
        return new TelegramCommandPoller(builderReturning(webClient), properties,
                killSwitch, positionBook, fakeNotifier);
    }

    private Map<String, Object> updateWith(String chatId, String text) {
        return Map.of(
                "update_id", 100,
                "message", Map.of(
                        "chat", Map.of("id", chatId),
                        "text", text));
    }

    @Test
    void stop_명령을_받으면_킬스위치가_작동한다() {
        TelegramCommandPoller poller = pollerWithUpdate(updateWith(CHAT_ID, "/stop"));

        poller.poll();

        assertTrue(killSwitch.isEngaged());
    }

    @Test
    void resume_명령을_받으면_킬스위치가_해제된다() {
        killSwitch.engage("사전 준비");
        TelegramCommandPoller poller = pollerWithUpdate(updateWith(CHAT_ID, "/resume"));

        poller.poll();

        assertFalse(killSwitch.isEngaged());
    }

    @Test
    void status_명령을_받으면_요약_알림을_보낸다() {
        TelegramCommandPoller poller = pollerWithUpdate(updateWith(CHAT_ID, "/status"));

        poller.poll();

        assertEquals(1, notices.size());
        assertTrue(notices.get(0).startsWith("INFO:"));
    }

    @Test
    void 타_chatId의_stop_명령은_킬스위치에_영향을_주지_않는다() {
        TelegramCommandPoller poller = pollerWithUpdate(updateWith("999999", "/stop"));

        poller.poll();

        assertFalse(killSwitch.isEngaged());
    }
}

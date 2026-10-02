package com.autostock.monitor;

import com.autostock.common.event.PositionRestored;
import com.autostock.common.util.Price;
import com.autostock.common.util.StockCode;
import com.autostock.common.util.StockNames;
import com.autostock.risk.KillSwitch;
import com.autostock.risk.RiskStateStore;
import com.autostock.portfolio.PositionBook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-06T00:00:00Z"));

    @BeforeEach
    void setUp() {
        killSwitch = new KillSwitch(published::add, mock(RiskStateStore.class), Clock.systemUTC());
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

    /** 폴링할 때마다 다음 update를 하나씩 돌려주는 WebClient(마지막 이후는 빈 결과). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private WebClient mockWebClientReturningSequence(List<Map<String, Object>> updates) {
        WebClient webClient = mock(WebClient.class, RETURNS_DEEP_STUBS);
        Mono[] responses = new Mono[updates.size() + 1];
        for (int i = 0; i < updates.size(); i++) {
            responses[i] = Mono.just(Map.of("ok", true, "result", List.of(updates.get(i))));
        }
        responses[updates.size()] = Mono.just(Map.of("ok", true, "result", List.of()));
        when(webClient.get()
                .uri(any(Function.class))
                .retrieve()
                .bodyToMono(Map.class))
                .thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));
        return webClient;
    }

    private TelegramCommandPoller pollerWithUpdates(List<Map<String, Object>> updates) {
        TelegramProperties properties = new TelegramProperties(true, "test-token", CHAT_ID);
        return new TelegramCommandPoller(builderReturning(mockWebClientReturningSequence(updates)), properties,
                killSwitch, positionBook, fakeNotifier, clock);
    }

    /** 가장 최근 알림에서 4자리 확인 코드를 꺼낸다. */
    private String issuedCode() {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/resume (\\d{4})").matcher(notices.get(notices.size() - 1));
        assertTrue(m.find(), notices.toString());
        return m.group(1);
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
                killSwitch, positionBook, fakeNotifier, clock);
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
    void resume_명령은_확인_코드만_보내고_아직_해제하지_않는다() {
        // Phase 0.7(D-09): 위험 명령 2단계 확인 — 잘못 누른 /resume 한 번으로 비상 정지가 풀리지 않는다
        killSwitch.engage("사전 준비");
        TelegramCommandPoller poller = pollerWithUpdate(updateWith(CHAT_ID, "/resume"));

        poller.poll();

        assertTrue(killSwitch.isEngaged());
        assertTrue(notices.get(0).startsWith("WARN:킬스위치 해제 확인"), notices.toString());
        issuedCode();
    }

    @Test
    void 코드가_틀리면_해제하지_않고_코드도_버린다() {
        killSwitch.engage("사전 준비");
        TelegramCommandPoller poller = pollerWithUpdates(List.of(updateWith(CHAT_ID, "/resume")));
        poller.poll();
        String code = issuedCode();
        String wrong = code.equals("0000") ? "0001" : "0000";

        poller.handleUpdate(updateWith(CHAT_ID, "/resume " + wrong));
        poller.handleUpdate(updateWith(CHAT_ID, "/resume " + code)); // 버려진 코드 — 맞아도 해제 안 됨

        assertTrue(killSwitch.isEngaged());
        assertTrue(notices.stream().anyMatch(n -> n.contains("확인 코드가 맞지 않습니다")), notices.toString());
        assertTrue(notices.get(notices.size() - 1).contains("없거나 만료"), notices.toString());
    }

    @Test
    void 맞는_코드를_60초_안에_보내면_해제된다() {
        killSwitch.engage("사전 준비");
        TelegramCommandPoller poller = pollerWithUpdates(List.of(updateWith(CHAT_ID, "/resume")));
        poller.poll();
        String code = issuedCode();
        clock.advance(Duration.ofSeconds(59));

        poller.handleUpdate(updateWith(CHAT_ID, "/resume " + code));

        assertFalse(killSwitch.isEngaged());
    }

    @Test
    void 만료된_코드로는_해제되지_않는다() {
        killSwitch.engage("사전 준비");
        TelegramCommandPoller poller = pollerWithUpdates(List.of(updateWith(CHAT_ID, "/resume")));
        poller.poll();
        String code = issuedCode();
        clock.advance(Duration.ofSeconds(61));

        poller.handleUpdate(updateWith(CHAT_ID, "/resume " + code));

        assertTrue(killSwitch.isEngaged());
        assertTrue(notices.get(notices.size() - 1).contains("없거나 만료"), notices.toString());
    }

    @Test
    void 이미_해제_상태면_확인_코드를_발급하지_않는다() {
        TelegramCommandPoller poller = pollerWithUpdate(updateWith(CHAT_ID, "/resume"));

        poller.poll();

        assertEquals(1, notices.size());
        assertTrue(notices.get(0).startsWith("INFO:킬스위치는 이미 해제 상태"), notices.toString());
    }

    @Test
    void status_명령을_받으면_요약_알림을_보낸다() {
        StockNames.learn("005930", "삼성전자", StockNames.Source.KIWOOM);
        positionBook.onPositionRestored(new PositionRestored(new StockCode("005930"), 19,
                new Price(new BigDecimal("259974"))));
        TelegramCommandPoller poller = pollerWithUpdate(updateWith(CHAT_ID, "/status"));

        poller.poll();

        assertEquals(1, notices.size());
        assertTrue(notices.get(0).startsWith("INFO:"));
        assertTrue(notices.get(0).contains("\n- 삼성전자(005930) 19주 @ 259974"), notices.get(0));
    }

    @Test
    void 타_chatId의_stop_명령은_킬스위치에_영향을_주지_않는다() {
        TelegramCommandPoller poller = pollerWithUpdate(updateWith("999999", "/stop"));

        poller.poll();

        assertFalse(killSwitch.isEngaged());
    }

    /** 테스트 전용 가변 시계. */
    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
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

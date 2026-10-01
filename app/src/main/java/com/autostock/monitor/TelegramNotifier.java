package com.autostock.monitor;

import com.autostock.common.util.SecretMasking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 텔레그램 Bot API로 알림을 보내는 어댑터 — Notifier Port의 실제 구현체.
 *
 * <p>{@code monitor.telegram.enabled=true}일 때만 빈으로 등록된다(기본 false).
 * 자택망에서 Bot API 실측 검증 전까지는 {@link LogOnlyNotifier}가 대신 등록된다.
 *
 * <p><b>실패 처리 원칙</b>: sendMessage 호출이 실패해도(네트워크 오류, 봇 차단, 잘못된
 * chat_id 등) 예외를 삼키고 error 로그만 남긴다 — 알림 발송 실패가 매매 흐름(리스너 체인)을
 * 막으면 절대 안 되기 때문이다. 알림은 부가 기능이지 매매의 전제조건이 아니다.
 *
 * <p><b>발송 제한(429)</b>(2026-10-01, aiDoc/alert-digest.md): 텔레그램은 같은 채팅에 초당 1건 정도를 권하고,
 * 넘기면 429와 함께 {@code parameters.retry_after}(초)를 돌려준다. 그만큼 기다렸다가 1회만 재시도한다.
 * 대기가 {@link #MAX_RETRY_WAIT}보다 길면 기다리지 않고 버린다 — notify는 호출 스레드에서 동기로 돌기 때문이다
 * (비동기 큐·발송 간격은 계획 Phase 1 알림 비동기화에서 다룬다).
 */
@Component
@ConditionalOnProperty(prefix = "monitor.telegram", name = "enabled", havingValue = "true")
public class TelegramNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    /** 429 재시도 대기 상한 — 이보다 길면 재시도하지 않는다(호출 스레드를 오래 붙잡지 않는다). */
    static final Duration MAX_RETRY_WAIT = Duration.ofSeconds(10);

    /** 429 응답 본문의 {@code "parameters":{"retry_after":N}}. */
    private static final Pattern RETRY_AFTER = Pattern.compile("\"retry_after\"\\s*:\\s*(\\d{1,6})");

    private final WebClient webClient;
    private final TelegramProperties properties;
    private final Sleeper sleeper;

    @Autowired
    public TelegramNotifier(WebClient.Builder builder, TelegramProperties properties) {
        this(builder, properties, Thread::sleep);
    }

    /** 테스트용 — 429 재시도 대기를 실제로 잠들지 않게 바꿔 끼운다. */
    TelegramNotifier(WebClient.Builder builder, TelegramProperties properties, Sleeper sleeper) {
        // 봇 토큰이 URL 경로 자체에 들어가는 텔레그램 Bot API 특성상 baseUrl만 고정하고
        // 토큰은 매 호출 시 경로 변수로 채운다(TokenManager처럼 헤더에 싣지 않음).
        this.webClient = builder.baseUrl("https://api.telegram.org").build();
        this.properties = properties;
        this.sleeper = sleeper;
    }

    @Override
    public void notify(NoticeLevel level, String message) {
        String text = "[%s] %s".formatted(level, message);
        try {
            send(text);
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 429) {
                retryOnceAfterLimit(level, message, text, e);
            } else {
                logFailure(level, message, e);
            }
        } catch (Exception e) {
            logFailure(level, message, e);
        }
    }

    private void send(String text) {
        // uri(String) 단일 인자 오버로드를 쓴다(가변인자 오버로드 대신) — 토큰을 담은
        // 경로를 미리 조립해서 넘기면 테스트에서 WebClient 체인을 스텁하기 쉬워진다.
        webClient.post()
                .uri("/bot%s/sendMessage".formatted(properties.botToken()))
                .bodyValue(Map.of(
                        "chat_id", properties.chatId(),
                        "text", text))
                .retrieve()
                .toBodilessEntity()
                // TODO 실측(자택망): getUpdates와 마찬가지로 sendMessage 응답 포맷/오류 코드도
                //  문서 기반 추정이다. 실제 봇으로 성공/실패 케이스를 확인해야 한다.
                .block();
    }

    /** 발송 제한(429) — retry_after만큼 기다렸다가 1회만 재시도한다. 상한을 넘으면 버린다(클래스 설명 참고). */
    private void retryOnceAfterLimit(NoticeLevel level, String message, String text, WebClientResponseException limited) {
        Duration wait = retryAfter(limited);
        if (wait.compareTo(MAX_RETRY_WAIT) > 0) {
            log.error("텔레그램 발송 제한(429, retry_after {}초 > 상한 {}초) — 재시도 없이 버림: level={} message={}",
                    wait.toSeconds(), MAX_RETRY_WAIT.toSeconds(), level, message);
            return;
        }
        log.warn("텔레그램 발송 제한(429) — {}초 뒤 1회 재시도", wait.toSeconds());
        try {
            sleeper.sleep(wait);
            send(text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logFailure(level, message, e);
        } catch (Exception e) {
            logFailure(level, message, e);
        }
    }

    /** 대기 시간 — 본문의 retry_after(초), 없으면 Retry-After 헤더, 그것도 없으면 1초. */
    static Duration retryAfter(WebClientResponseException e) {
        Matcher body = RETRY_AFTER.matcher(e.getResponseBodyAsString());
        if (body.find()) {
            return Duration.ofSeconds(Long.parseLong(body.group(1)));
        }
        String header = e.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        if (header != null && header.trim().matches("\\d{1,6}")) {
            return Duration.ofSeconds(Long.parseLong(header.trim()));
        }
        return Duration.ofSeconds(1);
    }

    private static void logFailure(NoticeLevel level, String message, Exception e) {
        // 알림 실패가 매매를 막으면 안 된다 — 여기서 끝낸다(재던지지 않음).
        // 예외 메시지에 봇 토큰이 담긴 전체 URL(/bot{token}/sendMessage)이 그대로 들어있을
        // 수 있어(WebClientResponseException 등) 마스킹 후 로그로 남긴다 — SecretMasking 참고.
        log.error("텔레그램 알림 발송 실패 — level={} message={}", level, message,
                SecretMasking.sanitizeForLogging(e));
    }

    /** 대기 함수 — 운영은 {@link Thread#sleep(Duration)}, 테스트는 기록만 한다. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}

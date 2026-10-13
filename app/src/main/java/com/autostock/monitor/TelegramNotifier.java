package com.autostock.monitor;

import com.autostock.common.util.MarketConstants;
import com.autostock.common.util.SecretMasking;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 텔레그램 Bot API로 알림을 보내는 어댑터 — Notifier Port의 실제 구현체.
 *
 * <p>{@code monitor.telegram.enabled=true}일 때만 빈으로 등록된다(기본 false).
 * 자택망에서 Bot API 실측 검증 전까지는 {@link LogOnlyNotifier}가 대신 등록된다.
 *
 * <p><b>실패 처리 원칙</b>: sendMessage 호출이 실패해도(네트워크 오류, 봇 차단, 잘못된
 * chat_id 등) 예외를 삼키고 로그만 남긴다 — 알림 발송 실패가 매매 흐름(리스너 체인)을
 * 막으면 절대 안 되기 때문이다. 알림은 부가 기능이지 매매의 전제조건이 아니다.
 *
 * <p><b>발송 제한(429)</b>(2026-10-01, aiDoc/alert-digest.md): 텔레그램은 같은 채팅에 초당 1건 정도를 권하고,
 * 넘기면 429와 함께 {@code parameters.retry_after}(초)를 돌려준다. 그만큼 기다렸다가 1회만 재시도한다.
 * 대기가 {@link #MAX_RETRY_WAIT}보다 길면 기다리지 않고 버린다 — notify는 호출 스레드에서 동기로 돌기 때문이다.
 * 체결·주문 요청·킬스위치 알림은 {@link NotificationDispatcher}의 전용 작업자 1개가 차례로 부르므로(실행 계획 1.3)
 * 이 대기가 WS 수신·주문 경로를 막지 않는다.
 *
 * <p><b>망 오류 지연 재전송</b>(2026-10-12, aiDoc/sleep-resume.md 5절): 응답을 받지 못한 실패(DNS·연결·타임아웃)와
 * 서버 오류(5xx)는 {@link #RESEND_DELAYS} 간격(30초·2분·5분·15분·30분·60분)으로 최대 6번 더 보낸다. PC가 대기에서 깨어난 직후에는 망이 늦게 붙어,
 * 그 순간 보내는 절전 복귀·장중 점검 경고가 정작 전부 사라졌다(10/12 11:19·18:05 실측). 재전송은 전용 데몬 스레드
 * 하나에서 돌고 호출 스레드를 붙잡지 않는다. 다시 보내는 알림 앞에는 처음 발생 시각을 붙인다.
 * 기다리는 알림은 {@link #MAX_PENDING_RESENDS}건까지만 붙든다 — 망이 오래 끊기면 그 뒤 알림은 기록만 하고 버린다.
 * 응답을 받은 다른 실패(400 잘못된 chat_id, 401·403 봇 토큰·차단)는 다시 보내도 같으므로 재전송하지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "monitor.telegram", name = "enabled", havingValue = "true")
public class TelegramNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    /** 429 재시도 대기 상한 — 이보다 길면 재시도하지 않는다(호출 스레드를 오래 붙잡지 않는다). */
    static final Duration MAX_RETRY_WAIT = Duration.ofSeconds(10);

    /**
     * 망 오류 재전송 간격 — n번째 재전송은 앞선 실패 뒤 n번째 값만큼 기다린다. 다 합쳐 약 1시간 50분 — 10/13 13:50~16:00처럼
     * 텔레그램 서버에 2시간 가까이 닿지 않는 일도 있었다(L-6 로그 점검).
     */
    static final List<Duration> RESEND_DELAYS = List.of(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(5),
            Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofMinutes(60));

    /** 재전송을 기다리는 알림 상한. */
    static final int MAX_PENDING_RESENDS = 20;

    /** 429 응답 본문의 {@code "parameters":{"retry_after":N}}. */
    private static final Pattern RETRY_AFTER = Pattern.compile("\"retry_after\"\\s*:\\s*(\\d{1,6})");

    private static final DateTimeFormatter OCCURRED_AT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final WebClient webClient;
    private final TelegramProperties properties;
    private final Sleeper sleeper;
    private final Clock clock;
    private final Resender resender;
    private final AtomicInteger pendingResends = new AtomicInteger();

    @Autowired
    public TelegramNotifier(WebClient.Builder builder, TelegramProperties properties, Clock clock) {
        this(builder, properties, Thread::sleep, clock, new DaemonResender());
    }

    /** 테스트용 — 429 대기와 재전송 예약을 실제로 기다리지 않게 바꿔 끼운다. */
    TelegramNotifier(WebClient.Builder builder, TelegramProperties properties, Sleeper sleeper, Clock clock,
                     Resender resender) {
        // 봇 토큰이 URL 경로 자체에 들어가는 텔레그램 Bot API 특성상 baseUrl만 고정하고
        // 토큰은 매 호출 시 경로 변수로 채운다(TokenManager처럼 헤더에 싣지 않음).
        this.webClient = builder.baseUrl("https://api.telegram.org").build();
        this.properties = properties;
        this.sleeper = sleeper;
        this.clock = clock;
        this.resender = resender;
    }

    @Override
    public void notify(NoticeLevel level, String message) {
        String text = "[%s] %s".formatted(level, message);
        try {
            send(text);
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 429) {
                retryOnceAfterLimit(level, message, text, e);
            } else if (resendable(e)) {
                resendLater(level, message, occurredAt(), e);
            } else {
                logFailure(level, message, e);
            }
        } catch (Exception e) {
            if (resendable(e)) {
                resendLater(level, message, occurredAt(), e);
            } else {
                logFailure(level, message, e);
            }
        }
    }

    private void send(String text) {
        // 토큰은 URI 템플릿 변수로 넘긴다. WebClient는 템플릿 문자열을 요청 지표 http.client.requests의
        // uri 태그로 남기므로, 경로를 미리 조립하면 봇 토큰이 /actuator/metrics에 그대로 보인다
        // (2026-10-06 로그 점검에서 확인 — aiDoc/upgrade-2026-10/14-log-review-2026-10-06.md).
        // 토큰의 ':'는 %3A로 인코딩된다 — getUpdates(TelegramCommandPoller)와 같은 방식이다.
        webClient.post()
                .uri("/bot{token}/sendMessage", properties.botToken())
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

    /** 다시 보내면 될 수 있는 실패 — 응답을 받지 못했거나(망 오류) 서버 오류(5xx)였다. */
    static boolean resendable(Exception e) {
        if (e instanceof WebClientRequestException) {
            return true;
        }
        return e instanceof WebClientResponseException response && response.getStatusCode().is5xxServerError();
    }

    /** 처음 실패 — 재전송 대기열에 넣는다. 대기열이 가득하면 버린다. */
    private void resendLater(NoticeLevel level, String message, String occurredAt, Exception cause) {
        if (pendingResends.incrementAndGet() > MAX_PENDING_RESENDS) {
            pendingResends.decrementAndGet();
            log.error("텔레그램 재전송 대기 {}건이 가득 — 이 알림은 버림: level={} message={}",
                    MAX_PENDING_RESENDS, level, message, SecretMasking.sanitizeForLogging(cause));
            return;
        }
        schedule(level, message, occurredAt, 0, cause);
    }

    /** {@code done}번 재전송한 뒤 실패 — 남은 횟수가 있으면 다음 재전송을 예약한다. */
    private void schedule(NoticeLevel level, String message, String occurredAt, int done, Exception cause) {
        Duration delay = RESEND_DELAYS.get(done);
        log.warn("텔레그램 알림 발송 실패({}) — {}초 뒤 다시 보낸다({}/{}): level={} message={}",
                SecretMasking.mask(cause.getClass().getSimpleName() + ": " + cause.getMessage()),
                delay.toSeconds(), done + 1, RESEND_DELAYS.size(), level, message);
        try {
            resender.schedule(() -> resend(level, message, occurredAt, done + 1), delay);
        } catch (RuntimeException e) {
            // 종료 중(실행기 닫힘) 등 — 더 기다리지 않는다
            pendingResends.decrementAndGet();
            logFailure(level, message, cause);
        }
    }

    private void resend(NoticeLevel level, String message, String occurredAt, int attempt) {
        try {
            send("[%s] (지연 발송 — %s 발생) %s".formatted(level, occurredAt, message));
            pendingResends.decrementAndGet();
            log.info("텔레그램 알림 지연 발송 성공({}번째 재전송): level={}", attempt, level);
        } catch (Exception e) {
            if (resendable(e) && attempt < RESEND_DELAYS.size()) {
                schedule(level, message, occurredAt, attempt, e);
            } else {
                pendingResends.decrementAndGet();
                logFailure(level, message, e);
            }
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

    private String occurredAt() {
        return ZonedDateTime.now(clock.withZone(MarketConstants.KST)).format(OCCURRED_AT);
    }

    private static void logFailure(NoticeLevel level, String message, Exception e) {
        // 알림 실패가 매매를 막으면 안 된다 — 여기서 끝낸다(재던지지 않음).
        // 예외 메시지에 봇 토큰이 담긴 전체 URL(/bot{token}/sendMessage)이 그대로 들어있을
        // 수 있어(WebClientResponseException 등) 마스킹 후 로그로 남긴다 — SecretMasking 참고.
        log.error("텔레그램 알림 발송 실패 — level={} message={}", level, message,
                SecretMasking.sanitizeForLogging(e));
    }

    @PreDestroy
    void shutdown() {
        if (resender instanceof DaemonResender daemon) {
            daemon.shutdown();
        }
    }

    /** 대기 함수 — 운영은 {@link Thread#sleep(Duration)}, 테스트는 기록만 한다. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** 재전송 예약 — 운영은 데몬 스레드 1개, 테스트는 기록만 하고 직접 돌린다. */
    @FunctionalInterface
    interface Resender {
        void schedule(Runnable task, Duration delay);
    }

    /** 운영 재전송 실행기 — 처음 쓸 때 데몬 스레드 1개를 만든다(실패가 없으면 스레드도 없다). */
    private static final class DaemonResender implements Resender {
        private ScheduledExecutorService executor;

        @Override
        public synchronized void schedule(Runnable task, Duration delay) {
            if (executor == null) {
                executor = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread thread = new Thread(r, "telegram-resend");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
        }

        synchronized void shutdown() {
            if (executor != null) {
                executor.shutdownNow();
            }
        }
    }
}

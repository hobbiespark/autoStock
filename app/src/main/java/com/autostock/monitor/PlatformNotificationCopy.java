package com.autostock.monitor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * VALUNIVO 4-3(구현 555·556, D286): 텔레그램 알림 봇으로 보낸 알림의 사본을 플랫폼 앱 알림 API에 넘긴다
 * ({@code POST /api/apps/{appId}/notifications}, mode=copy). 플랫폼은 니보 봇으로 "[앱 직접 발송 사본 · 니보 재발송]"을 붙여 다시 보내고
 * 대화 원장에 남긴다. 기본 꺼짐.
 *
 * <p>거래·알림 흐름을 막지 않는다: 호출 스레드는 큐에 넣기만 하고, 큐(100)가 차면 버리고 기록만 남긴다. 전송은 데몬 스레드 하나가
 * 하고 실패하면 2초 뒤 1회만 다시 보낸다(같은 notificationId라 플랫폼에서 멱등). 토큰은 파일 경로로만 받고 로그에 남기지 않는다.
 * 본문 내용도 로그에 남기지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "autostock.platform.notification-copy", name = "enabled", havingValue = "true")
public class PlatformNotificationCopy {

    private static final Logger log = LoggerFactory.getLogger(PlatformNotificationCopy.class);
    static final int MAX_TEXT = 2800;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final URI endpoint;
    private final String token;
    private final HttpClient client;
    private final ThreadPoolExecutor worker;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Duration retryDelay;

    public PlatformNotificationCopy(@Value("${autostock.platform.notification-copy.url:http://127.0.0.1:18080}") String baseUrl,
                                    @Value("${autostock.platform.notification-copy.app-id:autostock}") String appId,
                                    @Value("${autostock.platform.notification-copy.token-file}") String tokenFile) throws java.io.IOException {
        this(baseUrl, appId, Path.of(tokenFile), Duration.ofSeconds(2));
    }

    PlatformNotificationCopy(String baseUrl, String appId, Path tokenFile, Duration retryDelay) throws java.io.IOException {
        if (!appId.matches("[a-z][a-z0-9-]{0,39}")) throw new IllegalArgumentException("invalid notification-copy app-id");
        URI base = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        String host = base.getHost();
        // 플랫폼은 같은 PC의 루프백에서만 받는다. 다른 주소로 토큰을 보내지 않는다.
        if (!"http".equals(base.getScheme()) || !("127.0.0.1".equals(host) || "localhost".equals(host))) {
            throw new IllegalArgumentException("notification-copy url must be http on the loopback");
        }
        this.endpoint = URI.create(base + "/api/apps/" + appId + "/notifications");
        this.token = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
        if (token.isEmpty() || token.chars().anyMatch(c -> c < 0x21 || c > 0x7e)) throw new IllegalArgumentException("invalid notification-copy token file");
        this.retryDelay = retryDelay;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER)
                .proxy(java.net.ProxySelector.of(null)).build();
        this.worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(100), r -> {
            Thread t = new Thread(r, "platform-notification-copy");
            t.setDaemon(true);
            return t;
        }, (task, executor) -> log.warn("플랫폼 알림 사본 큐가 가득 — 이 사본은 버림"));
        log.info("플랫폼 알림 사본 켜짐 — {}", endpoint);
    }

    /** 플랫폼 응답 {"status":"accepted"|"duplicate"}의 값. 그 밖은 unknown(응답 본문은 기록하지 않는다). */
    static String result(String responseBody) {
        if (responseBody == null) return "unknown";
        var m = java.util.regex.Pattern.compile("\"status\"\\s*:\\s*\"(accepted|duplicate)\"").matcher(responseBody);
        return m.find() ? m.group(1) : "unknown";
    }

    /** 알림 봇 발송 결과(sent·failed·unknown)와 함께 사본을 맡긴다. 호출 스레드를 막지 않는다. */
    public void copy(NoticeLevel level, String message, String appSend) {
        String text = "[%s] %s".formatted(level, message);
        if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT - 1) + "…";
        byte[] id = new byte[12];
        RANDOM.nextBytes(id);
        String notificationId = "as-" + HexFormat.of().formatHex(id);
        String body = json.writeValueAsString(Map.of("notificationId", notificationId, "mode", "copy",
                "text", text, "appSend", appSend));
        worker.execute(() -> deliver(notificationId, appSend, body));
    }

    /** 결과는 ID·플랫폼 처리 결과·앱 발송 결과만 기록한다(VALUNIVO 562: 사본 도착을 로그로 확인). 본문·토큰은 남기지 않는다. */
    private void deliver(String notificationId, String appSend, String body) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status == 200) {
                    log.info("플랫폼 알림 사본 접수 — id={} 결과={} 앱발송={}", notificationId, result(response.body()), appSend);
                    return;
                }
                if (status < 500) { log.warn("플랫폼 알림 사본 거절 — id={} HTTP {}", notificationId, status); return; }
                log.warn("플랫폼 알림 사본 실패 — id={} HTTP {} ({}/2)", notificationId, status, attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("플랫폼 알림 사본 실패 — id={} {} ({}/2)", notificationId, e.getClass().getSimpleName(), attempt);
            }
            if (attempt == 1) {
                try { Thread.sleep(retryDelay.toMillis()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    /** 시험용: 큐에 남은 작업이 끝날 때까지 기다린다. */
    boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (worker.getQueue().isEmpty() && worker.getActiveCount() == 0) return true;
            Thread.sleep(20);
        }
        return false;
    }
}

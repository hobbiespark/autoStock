package com.autostock.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** VALUNIVO 4-3(556): 플랫폼 알림 사본 — 요청 모양, 재시도, 루프백 제한, 본문 길이. */
class PlatformNotificationCopyTest {

    @TempDir Path dir;
    HttpServer server;
    final List<String> bodies = new CopyOnWriteArrayList<>();
    final List<String> auth = new CopyOnWriteArrayList<>();
    final AtomicInteger failuresLeft = new AtomicInteger();
    Path token;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/apps/autostock/notifications", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            int status = failuresLeft.getAndDecrement() > 0 ? 503 : 200;
            byte[] out = "{\"status\":\"accepted\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        token = Files.writeString(dir.resolve("app-token"), "app_synthetic_token_1234\n");
    }

    @AfterEach
    void stop() { server.stop(0); }

    PlatformNotificationCopy copy() throws Exception {
        return new PlatformNotificationCopy("http://127.0.0.1:" + server.getAddress().getPort(), "autostock", token, Duration.ofMillis(50));
    }

    @Test
    void sendsCopyWithBearerTokenAndAppSendOutcome() throws Exception {
        var copy = copy();
        copy.copy(NoticeLevel.INFO, "체결 1건", "sent");
        assertTrue(copy.awaitIdle(Duration.ofSeconds(5)));
        assertEquals(1, bodies.size());
        JsonNode body = JsonMapper.builder().build().readTree(bodies.get(0));
        assertEquals("copy", body.path("mode").asString());
        assertEquals("sent", body.path("appSend").asString());
        assertEquals("[INFO] 체결 1건", body.path("text").asString());
        assertTrue(body.path("notificationId").asString().matches("as-[0-9a-f]{24}"));
        assertEquals("Bearer app_synthetic_token_1234", auth.get(0));
    }

    /** VALUNIVO 562: 접수되면 ID·결과·앱 발송 결과를 한 줄 남긴다. 본문·토큰은 남기지 않는다. */
    @Test
    void logsAcceptedCopyWithoutTextOrToken() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PlatformNotificationCopy.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var copy = copy();
            copy.copy(NoticeLevel.INFO, "비밀 아닌 본문", "sent");
            assertTrue(copy.awaitIdle(Duration.ofSeconds(5)));
            String id = JsonMapper.builder().build().readTree(bodies.get(0)).path("notificationId").asString();
            var lines = appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            assertTrue(lines.stream().anyMatch(l -> l.startsWith("플랫폼 알림 사본 켜짐")), lines.toString());
            assertTrue(lines.contains("플랫폼 알림 사본 접수 — id=" + id + " 결과=accepted 앱발송=sent"), lines.toString());
            assertTrue(lines.stream().noneMatch(l -> l.contains("본문") || l.contains("app_synthetic_token")), lines.toString());
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void resultReadsOnlyTheStatusValue() {
        assertEquals("accepted", PlatformNotificationCopy.result("{\"status\":\"accepted\"}"));
        assertEquals("duplicate", PlatformNotificationCopy.result("{\"status\" : \"duplicate\"}"));
        assertEquals("unknown", PlatformNotificationCopy.result("{\"status\":\"other\"}"));
        assertEquals("unknown", PlatformNotificationCopy.result(null));
    }

    @Test
    void retriesOnceOnServerErrorWithTheSameNotificationId() throws Exception {
        failuresLeft.set(1);
        var copy = copy();
        copy.copy(NoticeLevel.WARN, "재시도", "unknown");
        assertTrue(copy.awaitIdle(Duration.ofSeconds(5)));
        assertEquals(2, bodies.size());
        assertEquals(bodies.get(0), bodies.get(1));
    }

    @Test
    void longTextIsCutToThePlatformLimit() throws Exception {
        var copy = copy();
        copy.copy(NoticeLevel.INFO, "가".repeat(5000), "sent");
        assertTrue(copy.awaitIdle(Duration.ofSeconds(5)));
        assertEquals(PlatformNotificationCopy.MAX_TEXT, JsonMapper.builder().build().readTree(bodies.get(0)).path("text").asString().length());
    }

    @Test
    void refusesNonLoopbackUrlAndBadAppId() {
        assertThrows(IllegalArgumentException.class, () -> new PlatformNotificationCopy("http://example.com", "autostock", token, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new PlatformNotificationCopy("https://127.0.0.1:18080", "autostock", token, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new PlatformNotificationCopy("http://127.0.0.1:18080", "Auto Stock", token, Duration.ZERO));
    }
}

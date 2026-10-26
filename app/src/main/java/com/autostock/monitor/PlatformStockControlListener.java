package com.autostock.monitor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loopback entry for the platform's stock control forwarding (P2-2d, VALUNIVO 159 §5). Separate from the operator
 * handover listener (port, key and path differ) and never on the business web server. Binds 127.0.0.1 only; every
 * request is checked against the real socket addresses. No query, compression, chunking, redirect or CORS.
 * Authentication is the platform's signed proof inside the body ({@link PlatformStockControlEntry}).
 */
public final class PlatformStockControlListener implements AutoCloseable {
    public static final String PREFIX = "/platform/stock-control/v1/";
    public static final String HEALTH = PREFIX + "health", REQUESTS = PREFIX + "requests", OBSERVE = PREFIX + "observe", ACKS = PREFIX + "acks";
    private static final Logger log = LoggerFactory.getLogger(PlatformStockControlListener.class);
    private static final Map<String, String> POSTS = Map.of(REQUESTS, "requests", OBSERVE, "observe", ACKS, "acks");

    private final PlatformStockControlEntry entry;
    private final int requestedPort;
    private HttpServer server;
    private ExecutorService executor;
    private volatile int boundPort = -1;

    /** {@code port} 0 is an ephemeral port for tests only; the configuration requires an explicit port. */
    public PlatformStockControlListener(PlatformStockControlEntry entry, int port) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("invalid-stock-control-port");
        }
        this.entry = Objects.requireNonNull(entry);
        this.requestedPort = port;
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("stock-control-listener-already-started");
        }
        var created = HttpServer.create(new InetSocketAddress(HandoverOperatorListener.loopbackAddress(), requestedPort), 8);
        executor = Executors.newFixedThreadPool(2, runnable -> {
            var thread = new Thread(runnable, "platform-stock-control-entry");
            thread.setDaemon(true);
            return thread;
        });
        created.setExecutor(executor);
        created.createContext("/", this::handle);
        created.start();
        server = created;
        boundPort = created.getAddress().getPort();
        log.info("플랫폼 주식 제어 입구 시작 — 127.0.0.1:{} (health/requests/observe/acks)", port());
    }

    public int port() {
        if (boundPort < 0) {
            throw new IllegalStateException("stock-control-listener-not-started");
        }
        return boundPort;
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(1);
            executor.shutdownNow();
            server = null;
            boundPort = -1;
            log.info("플랫폼 주식 제어 입구 종료");
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        PlatformStockControlEntry.Answer answer;
        try {
            answer = respond(exchange);
        } catch (RuntimeException unexpected) {
            answer = PlatformStockControlEntry.error(503, "stock-control-unavailable");
        }
        byte[] bytes = answer.body();
        try (exchange) {
            exchange.getRequestBody().close();
            var headers = exchange.getResponseHeaders();
            headers.set("Cache-Control", "no-store");
            if (bytes.length > 0) {
                headers.set("Content-Type", "application/json; charset=utf-8");
            }
            exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
        } catch (IOException lost) {
            // Response lost: the platform marks the entry unknown and reconciles through observe only.
        }
        log.info("플랫폼 주식 제어 입구 요청 — status={} code={}", answer.status(), answer.code());
    }

    private PlatformStockControlEntry.Answer respond(HttpExchange exchange) throws IOException {
        if (!HandoverOperatorListener.loopback(exchange.getLocalAddress(), exchange.getRemoteAddress(), port())) {
            return PlatformStockControlEntry.error(403, "stock-control-denied");
        }
        var uri = exchange.getRequestURI();
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawAuthority() != null) {
            return PlatformStockControlEntry.error(400, "stock-control-malformed");
        }
        String path = uri.getRawPath();
        var headers = exchange.getRequestHeaders();
        if (headers.containsKey("Origin")) {
            return PlatformStockControlEntry.error(403, "stock-control-denied");
        }
        if (headers.containsKey("Content-Encoding") || headers.containsKey("Transfer-Encoding")) {
            return PlatformStockControlEntry.error(400, "stock-control-malformed");
        }
        if (HEALTH.equals(path)) {
            if (!"GET".equals(exchange.getRequestMethod())) {
                return PlatformStockControlEntry.error(405, "stock-control-method-not-allowed");
            }
            List<String> lengths = headers.get("Content-Length");
            if (lengths != null && !(lengths.size() == 1 && "0".equals(lengths.getFirst()))) {
                return PlatformStockControlEntry.error(400, "stock-control-malformed");
            }
            return entry.ready() ? new PlatformStockControlEntry.Answer(200, "stock-control-ready", new byte[0])
                    : PlatformStockControlEntry.error(503, "stock-control-unavailable");
        }
        String action = POSTS.get(path);
        if (action == null) {
            return PlatformStockControlEntry.error(404, "stock-control-not-found");
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            return PlatformStockControlEntry.error(405, "stock-control-method-not-allowed");
        }
        List<String> lengths = headers.get("Content-Length");
        if (lengths == null || lengths.size() != 1 || !lengths.getFirst().matches("[0-9]{1,6}")) {
            return PlatformStockControlEntry.error(400, "stock-control-malformed");
        }
        int length = Integer.parseInt(lengths.getFirst());
        if (length > PlatformStockControlEntry.MAX_BODY) {
            return PlatformStockControlEntry.error(413, "stock-control-too-large");
        }
        if (length == 0) {
            return PlatformStockControlEntry.error(400, "stock-control-malformed");
        }
        byte[] body = exchange.getRequestBody().readNBytes(length);
        if (body.length != length) {
            return PlatformStockControlEntry.error(400, "stock-control-malformed");
        }
        return switch (action) {
            case "requests" -> entry.request(body);
            case "observe" -> entry.observe(body);
            default -> entry.ack(body);
        };
    }
}

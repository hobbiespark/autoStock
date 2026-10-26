package com.autostock.monitor;

import com.autostock.monitor.control.LegacyResumeGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local operator resume listener (P2-2d). Its own 127.0.0.1 port, key and path — deliberately not a route of the
 * platform-facing operator listener (stop/observe only, decision 2026-10-26) or of the business web server. Same socket,
 * header and size checks as {@link HandoverOperatorListener}. Only {@code POST /legacy-receiver/resume}.
 */
public final class LegacyResumeListener implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(LegacyResumeListener.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BODY = 4096;

    private record Reply(int status, String code, ObjectNode body) {}

    private final LegacyResumeGate gate;
    private final LegacyResumeOperator operator;
    private final int requestedPort;
    private HttpServer server;
    private ExecutorService executor;
    private volatile int boundPort = -1;

    /** {@code port} 0 is an ephemeral port for tests only; the configuration requires an explicit port. */
    public LegacyResumeListener(LegacyResumeGate gate, LegacyResumeOperator operator, int port) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("invalid-legacy-resume-port");
        }
        this.gate = Objects.requireNonNull(gate);
        this.operator = Objects.requireNonNull(operator);
        this.requestedPort = port;
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("legacy-resume-listener-already-started");
        }
        var created = HttpServer.create(new InetSocketAddress(HandoverOperatorListener.loopbackAddress(), requestedPort), 4);
        executor = Executors.newSingleThreadExecutor(runnable -> {
            var thread = new Thread(runnable, "legacy-resume-listener");
            thread.setDaemon(true);
            return thread;
        });
        created.setExecutor(executor);
        created.createContext("/", this::handle);
        created.start();
        server = created;
        boundPort = created.getAddress().getPort();
        log.info("로컬 운영자 legacy 수신 재개 listener 시작 — 127.0.0.1:{}", port());
    }

    public int port() {
        if (boundPort < 0) {
            throw new IllegalStateException("legacy-resume-listener-not-started");
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
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        Reply reply;
        try {
            reply = respond(exchange);
        } catch (RuntimeException unexpected) {
            reply = error(503, "legacy-resume-unavailable");
        }
        byte[] bytes = JSON.writeValueAsBytes(reply.body());
        try (exchange) {
            exchange.getRequestBody().close();
            var headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "application/json; charset=utf-8");
            headers.set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException lost) {
            // The ledger row already holds the decision; a repeated request never resumes twice.
        }
        log.info("로컬 운영자 legacy 수신 재개 요청 처리 — status={} code={}", reply.status(), reply.code());
    }

    private Reply respond(HttpExchange exchange) throws IOException {
        if (!HandoverOperatorListener.loopback(exchange.getLocalAddress(), exchange.getRemoteAddress(), port())) {
            return error(403, "legacy-resume-denied");
        }
        var uri = exchange.getRequestURI();
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawAuthority() != null) {
            return error(400, "legacy-resume-malformed");
        }
        if (!LegacyResumeGate.PATH.equals(uri.getRawPath())) {
            return error(404, "legacy-resume-not-found");
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            return error(405, "legacy-resume-method-not-allowed");
        }
        var headers = exchange.getRequestHeaders();
        if (headers.containsKey("Origin")) {
            return error(403, "legacy-resume-denied");
        }
        if (headers.containsKey("Content-Encoding") || headers.containsKey("Transfer-Encoding")) {
            return error(400, "legacy-resume-malformed");
        }
        List<String> lengths = headers.get("Content-Length");
        if (lengths == null || lengths.size() != 1 || !lengths.getFirst().matches("[0-9]{1,6}")) {
            return error(400, "legacy-resume-malformed");
        }
        int length = Integer.parseInt(lengths.getFirst());
        if (length > MAX_BODY) {
            return error(413, "legacy-resume-too-large");
        }
        if (length == 0) {
            return error(400, "legacy-resume-malformed");
        }
        byte[] body = exchange.getRequestBody().readNBytes(length);
        if (body.length != length) {
            return error(400, "legacy-resume-malformed");
        }
        LegacyResumeGate.Verified verified;
        try {
            verified = gate.verify("POST", LegacyResumeGate.PATH, single(headers.get(LegacyResumeGate.KEY_ID_HEADER)),
                    single(headers.get(LegacyResumeGate.SIGNATURE_HEADER)), body);
        } catch (SecurityException denied) {
            return error(403, "legacy-resume-denied");
        }
        var outcome = operator.resume(verified);
        var node = JSON.createObjectNode();
        node.put("contract_version", "1.0");
        node.put("request_id", verified.request().requestId().toString());
        node.put("result", outcome.result());
        if (outcome.reason() != null) node.put("reason", outcome.reason());
        if (outcome.stopId() != null) node.put("stop_id", outcome.stopId().toString());
        if (outcome.legacyNextOffset() != null) node.put("legacy_next_offset", outcome.legacyNextOffset());
        return new Reply(outcome.status(), outcome.reason() == null ? outcome.result() : outcome.reason(), node);
    }

    private static Reply error(int status, String code) {
        var body = JSON.createObjectNode();
        body.put("contract_version", "1.0");
        body.put("error", code);
        return new Reply(status, code, body);
    }

    private static String single(List<String> values) {
        return values == null || values.size() != 1 ? null : values.getFirst();
    }
}

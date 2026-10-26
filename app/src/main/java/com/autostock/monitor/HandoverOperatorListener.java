package com.autostock.monitor;

import com.autostock.monitor.control.HandoverOperatorGate;
import com.autostock.monitor.control.HandoverOperatorSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Separate loopback operator listener for the receiver handover (VALUNIVO R16, 143 adopted design).
 * Binds 127.0.0.1 only, never the business web server. Exposes POST stop/observe only: no withdraw, resume,
 * query, compression, redirect or CORS. Every request is checked against the real socket addresses; Host and
 * Forwarded headers are never used for authentication. The HMAC gate, durable ledger and coordinator decide the rest.
 */
public final class HandoverOperatorListener implements AutoCloseable {
    public static final String STOP_PATH = "/receiver-handover/stop", OBSERVE_PATH = "/receiver-handover/observe";
    private static final Logger log = LoggerFactory.getLogger(HandoverOperatorListener.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BODY = 4096;

    record Reply(int status, String code, ObjectNode body) {}

    private final HandoverOperatorGate gate;
    private final OperatorHandoverCoordinator coordinator;
    private final TelegramReceiverStopStore stops;
    private final int requestedPort;
    private HttpServer server;
    private ExecutorService executor;
    private volatile int boundPort = -1;

    /** {@code port} 0 is an ephemeral port for tests only; the configuration requires an explicit port. */
    public HandoverOperatorListener(HandoverOperatorGate gate, OperatorHandoverCoordinator coordinator,
                                    TelegramReceiverStopStore stops, int port) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("invalid-operator-listener-port");
        }
        this.gate = Objects.requireNonNull(gate);
        this.coordinator = Objects.requireNonNull(coordinator);
        this.stops = Objects.requireNonNull(stops);
        this.requestedPort = port;
    }

    static InetAddress loopbackAddress() {
        try {
            return InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("operator-listener-already-started");
        }
        var created = HttpServer.create(new InetSocketAddress(loopbackAddress(), requestedPort), 8);
        executor = Executors.newFixedThreadPool(2, runnable -> {
            var thread = new Thread(runnable, "operator-handover-listener");
            thread.setDaemon(true);
            return thread;
        });
        created.setExecutor(executor);
        created.createContext("/", this::handle);
        created.start();
        server = created;
        boundPort = created.getAddress().getPort();
        log.info("수신 이관 운영자 listener 시작 — 127.0.0.1:{} (stop/observe만)", port());
    }

    public int port() {
        if (boundPort < 0) {
            throw new IllegalStateException("operator-listener-not-started");
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
            log.info("수신 이관 운영자 listener 종료");
        }
    }

    /** Both ends of the accepted socket must be loopback, and the local end must be this listener's own port. */
    static boolean loopback(InetSocketAddress local, InetSocketAddress remote, int expectedPort) {
        return local != null && remote != null && local.getAddress() != null && remote.getAddress() != null
                && local.getAddress().isLoopbackAddress() && remote.getAddress().isLoopbackAddress()
                && local.getPort() == expectedPort;
    }

    private void handle(HttpExchange exchange) throws IOException {
        Reply reply;
        try {
            reply = respond(exchange);
        } catch (RuntimeException unexpected) {
            reply = error(503, "operator-handover-unavailable-use-observe");
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
            // Response lost: the caller recovers with a fresh observe, never by resending stop.
        }
        log.info("수신 이관 운영자 요청 처리 — status={} code={}", reply.status(), reply.code());
    }

    private Reply respond(HttpExchange exchange) throws IOException {
        if (!loopback(exchange.getLocalAddress(), exchange.getRemoteAddress(), port())) {
            return error(403, "operator-handover-denied");
        }
        var uri = exchange.getRequestURI();
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawAuthority() != null) {
            return error(400, "operator-handover-malformed");
        }
        String path = uri.getRawPath();
        if (!STOP_PATH.equals(path) && !OBSERVE_PATH.equals(path)) {
            return error(404, "operator-handover-not-found");
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            return error(405, "operator-handover-method-not-allowed");
        }
        var headers = exchange.getRequestHeaders();
        if (headers.containsKey("Origin")) {
            return error(403, "operator-handover-denied");
        }
        if (headers.containsKey("Content-Encoding") || headers.containsKey("Transfer-Encoding")) {
            return error(400, "operator-handover-malformed");
        }
        List<String> lengths = headers.get("Content-Length");
        if (lengths == null || lengths.size() != 1 || !lengths.getFirst().matches("[0-9]{1,6}")) {
            return error(400, "operator-handover-malformed");
        }
        int length = Integer.parseInt(lengths.getFirst());
        if (length > MAX_BODY) {
            return error(413, "operator-handover-too-large");
        }
        if (length == 0) {
            return error(400, "operator-handover-malformed");
        }
        byte[] body = exchange.getRequestBody().readNBytes(length);
        if (body.length != length) {
            return error(400, "operator-handover-malformed");
        }
        HandoverOperatorGate.Verified verified;
        try {
            verified = gate.verify("POST", path, single(headers.get(HandoverOperatorSigner.KEY_ID_HEADER)),
                    single(headers.get(HandoverOperatorSigner.SIGNATURE_HEADER)), body);
        } catch (SecurityException denied) {
            return error(403, "operator-handover-denied");
        }
        return verified.request().action() == HandoverOperatorGate.Action.stop ? stop(verified) : observe(verified);
    }

    private Reply stop(HandoverOperatorGate.Verified verified) {
        try {
            if (stops.active().isPresent()) {
                // Not received: an existing stop is never adopted as this request's success or re-confirmed.
                return error(409, "operator-receiver-already-stopped");
            }
            var stop = coordinator.stop(verified);
            var body = envelope(verified);
            body.put("result", "stopped");
            body.set("stop", stop(stop, null));
            return new Reply(200, "stopped", body);
        } catch (SecurityException denied) {
            return "operator-request-id-conflict".equals(denied.getMessage())
                    ? error(409, "operator-request-id-conflict") : error(403, "operator-handover-denied");
        } catch (IllegalStateException failed) {
            String code = failed.getMessage();
            if ("operator-request-already-received-use-observe".equals(code)) {
                return error(409, code);
            }
            return error(503, "operator-stop-outcome-unconfirmed-use-observe".equals(code)
                    ? code : "operator-handover-unavailable-use-observe");
        }
    }

    private Reply observe(HandoverOperatorGate.Verified verified) {
        try {
            var found = coordinator.observe(verified);
            if (found.isEmpty()) {
                return error(404, "operator-request-not-found");
            }
            var observed = found.get();
            var body = envelope(verified);
            body.put("target_request_id", verified.request().targetRequestId().toString());
            body.put("result", "observed");
            body.put("state", observed.state());
            body.put("received_at", observed.receipt().receivedAt().toString());
            body.put("request_expires_at", observed.receipt().expiresAt().toString());
            if (observed.attempt().isPresent()) {
                var attempt = body.putObject("attempt");
                attempt.put("state", observed.attempt().get().state());
                attempt.put("execute_before", observed.attempt().get().executeBefore().toString());
            } else {
                body.putNull("attempt");
            }
            if (observed.stop().isPresent()) {
                body.set("stop", stop(observed.stop().get().stop(), observed.stop().get().active()));
            } else {
                body.putNull("stop");
            }
            return new Reply(200, "observed", body);
        } catch (SecurityException denied) {
            return error(403, "operator-handover-denied");
        } catch (IllegalStateException unavailable) {
            return error(503, "operator-handover-unavailable-use-observe");
        }
    }

    private static ObjectNode envelope(HandoverOperatorGate.Verified verified) {
        var body = JSON.createObjectNode();
        body.put("contract_version", "1.0");
        body.put("request_id", verified.request().requestId().toString());
        body.put("action", verified.request().action().name());
        return body;
    }

    private static ObjectNode stop(TelegramReceiverStopStore.Stop stop, Boolean active) {
        var node = JSON.createObjectNode();
        node.put("stop_id", stop.stopId().toString());
        node.put("confirmed_next_offset", stop.confirmedNextOffset());
        node.put("pending_resume_dropped", stop.pendingResumeDropped());
        node.put("instance_id", stop.instanceId());
        node.put("recorded_at", stop.recordedAt().toString());
        if (active != null) {
            node.put("active", active);
        }
        return node;
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

package com.autostock.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

/**
 * Independent copy of the platform side of the stock control contract (VALUNIVO feat/p2-2c-handover-runner eaa7a2a:
 * StockControlProofIssuer 133, StockControlResultVerifier 137, StockControlResultAckIssuer 138, request fingerprint and the
 * HTTP client of TelegramReceiverConfiguration.stockApp). It deliberately uses no autoStock control class, so a drift on
 * either side fails the entry tests. Synthetic keys only.
 */
final class PlatformStockControlReplica {
    static final String SPACE = "sp_self", ACTOR = "b".repeat(64), REQUEST_KID = "platform_test", ACK_KID = "platform_ack_test", RESULT_KID = "autostock_test";
    private static final ObjectMapper JSON = new ObjectMapper();
    final KeyPair requestPair = keys(), ackPair = keys(), resultPair = keys();
    final String bot = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY).build();

    record Response(int status, byte[] body) { String text() { return new String(body, StandardCharsets.UTF_8); } }
    record Wire(String update, String fence, String command, String code) {}
    record Result(UUID attemptId, String outcome, String code, String reply, String resultSha256, String requestSha256) {}

    static KeyPair keys() {
        try { return KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** TelegramStockControlWire.encode: ObjectNode in this field order, null confirmation_code when absent. */
    byte[] wire(Wire w) throws IOException {
        var root = JSON.createObjectNode();
        root.put("contract_version", "1.0"); root.put("app_id", "autostock"); root.put("bot_ref", bot); root.put("space_id", SPACE); root.put("actor_ref", ACTOR);
        root.put("source_update_id", w.update()); root.put("receiver_fence", w.fence()); root.put("command", w.command());
        if (w.code() == null) root.putNull("confirmation_code"); else root.put("confirmation_code", w.code());
        return JSON.writeValueAsBytes(root);
    }

    /** TelegramStockControlRequest.fingerprint: SHA-256 over 4-byte length framed fields. */
    String fingerprint(Wire w) throws Exception {
        var bytes = new ByteArrayOutputStream(); var framed = new DataOutputStream(bytes);
        for (String field : List.of("valunivo-telegram-stock-control-v1", "1.0", "autostock", bot, SPACE, ACTOR, w.update(), w.fence(), w.command(), w.code() == null ? "" : w.code())) {
            byte[] utf8 = field.getBytes(StandardCharsets.UTF_8); framed.writeInt(utf8.length); framed.write(utf8);
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    }

    /** StockControlProofIssuer.issue (30 s, never past the lease). */
    byte[] proof(Wire w, Instant issued) throws Exception { return proof(w, issued, issued.plusSeconds(30), requestPair.getPrivate()); }

    byte[] proof(Wire w, Instant issued, Instant expires, PrivateKey key) throws Exception {
        String base64 = Base64.getEncoder().encodeToString(wire(w));
        var signature = Signature.getInstance("Ed25519"); signature.initSign(key);
        signature.update(frame("valunivo-telegram-stock-control-proof-v1", REQUEST_KID, issued.toString(), expires.toString(), base64));
        return JSON.writeValueAsBytes(Map.of("contract_version", "1.0", "key_id", REQUEST_KID, "issued_at", issued.toString(), "expires_at", expires.toString(),
                "request_base64", base64, "signature", Base64.getEncoder().encodeToString(signature.sign())));
    }

    /** StockControlResultVerifier.verify with the same field sets, framing, scope, hash and freshness checks. */
    Result verify(byte[] envelope, String expectedUpdate, String expectedFingerprint, Instant now) throws Exception {
        JsonNode root = JSON.readTree(envelope);
        fields(root, Set.of("contract_version", "key_id", "issued_at", "expires_at", "statement_base64", "signature"));
        require(root.get("contract_version").textValue().equals("1.0") && root.get("key_id").textValue().equals(RESULT_KID));
        String issuedText = root.get("issued_at").textValue(), expiresText = root.get("expires_at").textValue();
        Instant issued = Instant.parse(issuedText), expires = Instant.parse(expiresText);
        require(issued.toString().equals(issuedText) && expires.toString().equals(expiresText) && expires.isAfter(issued)
                && !expires.isAfter(issued.plusSeconds(30)) && !issued.isAfter(now) && now.isBefore(expires));
        String encoded = root.get("statement_base64").textValue();
        var check = Signature.getInstance("Ed25519"); check.initVerify(resultPair.getPublic());
        check.update(frame("autostock-telegram-control-result-proof-v1", RESULT_KID, issuedText, expiresText, encoded));
        require(check.verify(Base64.getDecoder().decode(root.get("signature").textValue())));
        JsonNode s = JSON.readTree(Base64.getDecoder().decode(encoded));
        fields(s, Set.of("contract_version", "app_id", "bot_ref", "space_id", "actor_ref", "source_update_id", "attempt_id", "request_sha256", "outcome", "result_sha256", "content_base64"));
        require(s.get("app_id").textValue().equals("autostock") && s.get("bot_ref").textValue().equals(bot) && s.get("space_id").textValue().equals(SPACE)
                && s.get("actor_ref").textValue().equals(ACTOR) && s.get("source_update_id").textValue().equals(expectedUpdate)
                && s.get("request_sha256").textValue().equals(expectedFingerprint));
        byte[] content = Base64.getDecoder().decode(s.get("content_base64").textValue());
        require(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)).equals(s.get("result_sha256").textValue()));
        JsonNode c = JSON.readTree(content);
        fields(c, Set.of("outcome", "code", "reply"));
        require(c.get("outcome").textValue().equals(s.get("outcome").textValue()) && Set.of("succeeded", "failed").contains(c.get("outcome").textValue())
                && c.get("code").textValue().matches("[a-z][a-z0-9-]{0,63}") && !c.get("reply").textValue().isBlank());
        return new Result(UUID.fromString(s.get("attempt_id").textValue()), c.get("outcome").textValue(), c.get("code").textValue(), c.get("reply").textValue(),
                s.get("result_sha256").textValue(), s.get("request_sha256").textValue());
    }

    /** StockControlResultAckIssuer.issue for a platform-durable result. */
    byte[] ack(String update, Result result, byte[] resultProof, Instant platformRecordedAt, Instant issued) throws Exception {
        var statement = JSON.createObjectNode();
        statement.put("contract_version", "1.0"); statement.put("app_id", "autostock"); statement.put("bot_ref", bot); statement.put("space_id", SPACE); statement.put("actor_ref", ACTOR);
        statement.put("source_update_id", update); statement.put("attempt_id", result.attemptId().toString()); statement.put("request_sha256", result.requestSha256());
        statement.put("outcome", result.outcome()); statement.put("result_sha256", result.resultSha256());
        statement.put("result_proof_sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(resultProof)));
        statement.put("platform_recorded_at", platformRecordedAt.toString());
        String encoded = Base64.getEncoder().encodeToString(JSON.writeValueAsBytes(statement)), expires = issued.plusSeconds(30).toString();
        var signature = Signature.getInstance("Ed25519"); signature.initSign(ackPair.getPrivate());
        signature.update(frame("valunivo-telegram-control-result-ack-v1", ACK_KID, issued.toString(), expires, encoded));
        return JSON.writeValueAsBytes(Map.of("contract_version", "1.0", "key_id", ACK_KID, "issued_at", issued.toString(), "expires_at", expires,
                "statement_base64", encoded, "signature", Base64.getEncoder().encodeToString(signature.sign())));
    }

    /** TelegramReceiverConfiguration.stockApp: GET health, POST requests|observe|acks with Content-Type application/json. */
    boolean available(int port) {
        try {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/platform/stock-control/v1/health")).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception down) {
            return false;
        }
    }

    Response post(int port, String path, byte[] body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/platform/stock-control/v1/" + path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        return new Response(response.statusCode(), response.body());
    }

    private static byte[] frame(String... fields) throws IOException {
        var bytes = new ByteArrayOutputStream(); var data = new DataOutputStream(bytes);
        for (String field : fields) { byte[] utf8 = field.getBytes(StandardCharsets.UTF_8); data.writeInt(utf8.length); data.write(utf8); }
        return bytes.toByteArray();
    }

    private static void fields(JsonNode node, Set<String> expected) {
        var actual = new HashSet<String>(); node.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(expected));
    }

    private static void require(boolean ok) { if (!ok) throw new SecurityException("replica-contract-mismatch"); }
}

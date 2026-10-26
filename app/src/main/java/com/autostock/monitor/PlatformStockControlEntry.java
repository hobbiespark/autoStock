package com.autostock.monitor;

import com.autostock.monitor.control.*;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Platform stock control entry decisions (P2-2d, VALUNIVO 159 §5). Transport-free so the HTTP listener only frames bytes.
 * requests: 132 verify -> 134 receipt -> 135 single attempt -> shared {@link TelegramStockCommands} -> 136 result -> 137 proof.
 * A repeated (bot,update) never executes again; observe and acks never create a receipt, an attempt or an execution.
 * Authority is the platform's signed proof plus the app's own checks: no trading or order permission is added (PB-05).
 */
public final class PlatformStockControlEntry {
    private static final Logger log = LoggerFactory.getLogger(PlatformStockControlEntry.class);
    static final int MAX_BODY = 8192;
    private static final Set<String> PROOF_FIELDS = Set.of("contract_version", "key_id", "issued_at", "expires_at", "request_base64", "signature");
    private static final Set<String> ACK_FIELDS = Set.of("contract_version", "key_id", "issued_at", "expires_at", "statement_base64", "signature");
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(2).maxStringLength(6000).maxNumberLength(20).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** {@code body} is the 137 result proof for 200 on requests/observe, otherwise a small JSON status. */
    public record Answer(int status, String code, byte[] body) {
        @Override public String toString() { return "StockControlAnswer[status=" + status + ",code=" + code + "]"; }
    }

    private final StockControlVerifier verifier;
    private final StockControlReceiptStore receipts;
    private final StockControlAttemptStore attempts;
    private final StockControlResultIssuer issuer;
    private final StockControlResultAckVerifier ackVerifier;
    private final StockControlResultAckStore acks;
    private final TelegramStockCommands commands;
    private final DataSource source;
    /** Commands run one at a time: a /resume confirmation never races its own code request. */
    private final ReentrantLock lock = new ReentrantLock();

    public PlatformStockControlEntry(StockControlVerifier verifier, StockControlReceiptStore receipts, StockControlAttemptStore attempts,
                                     StockControlResultIssuer issuer, StockControlResultAckVerifier ackVerifier, StockControlResultAckStore acks,
                                     TelegramStockCommands commands, DataSource source) {
        this.verifier = Objects.requireNonNull(verifier);
        this.receipts = Objects.requireNonNull(receipts);
        this.attempts = Objects.requireNonNull(attempts);
        this.issuer = Objects.requireNonNull(issuer);
        this.ackVerifier = Objects.requireNonNull(ackVerifier);
        this.acks = Objects.requireNonNull(acks);
        this.commands = Objects.requireNonNull(commands);
        this.source = Objects.requireNonNull(source);
    }

    /** health: no execution. Keys, stores and the command adapter exist by construction; only the ledger DB is probed. */
    public boolean ready() {
        try (var c = source.getConnection()) {
            return c.isValid(2);
        } catch (Exception down) {
            return false;
        }
    }

    public Answer request(byte[] body) {
        if (!wellFormed(body, PROOF_FIELDS)) return error(400, "stock-control-malformed");
        StockControlVerifier.Verified value;
        try {
            value = verifier.verify(body);
        } catch (SecurityException denied) {
            return error(403, "stock-control-proof-denied");
        }
        lock.lock();
        try {
            StockControlReceiptStore.Receipt receipt;
            try {
                receipt = receipts.receive(value);
            } catch (SecurityException denied) {
                return "stock-control-update-conflict".equals(denied.getMessage())
                        ? error(409, "stock-control-update-conflict") : error(403, "stock-control-proof-denied");
            }
            if (receipt.duplicate()) {
                return stored(value);
            }
            StockControlAttemptStore.Claim claim;
            try {
                claim = attempts.begin(value);
            } catch (SecurityException denied) {
                return error(403, "stock-control-proof-denied");
            } catch (IllegalStateException attempted) {
                if ("stock-control-already-attempted".equals(attempted.getMessage())) return stored(value);
                throw attempted;
            }
            return execute(value, claim);
        } catch (IllegalStateException unavailable) {
            return error(503, "stock-control-unavailable");
        } finally {
            lock.unlock();
        }
    }

    private Answer execute(StockControlVerifier.Verified value, StockControlAttemptStore.Claim claim) {
        var request = value.request();
        TelegramStockCommands.Reply reply;
        try {
            attempts.requireCurrent(claim, value);
        } catch (RuntimeException expired) {
            // Not executed, but the attempt cannot be closed with a result any more: conservative unknown, never retried.
            unknown(claim);
            return pending("stock-control-unknown");
        }
        try {
            reply = switch (request.command()) {
                case stop -> commands.stop();
                case resume_request -> commands.requestResume();
                case resume_confirm -> commands.confirmResume(request.confirmationCode());
                case status -> commands.status();
            };
        } catch (RuntimeException failed) {
            log.error("플랫폼 주식 제어 명령 실행 결과 불명 — command={} (재실행 없음)", request.command());
            unknown(claim);
            return pending("stock-control-unknown");
        }
        var result = new StockControlResultCipher.Result(reply.succeeded() ? StockControlResultCipher.Outcome.succeeded
                : StockControlResultCipher.Outcome.failed, reply.code(), reply.reply());
        try {
            attempts.complete(claim, value, result);
        } catch (RuntimeException unrecorded) {
            log.error("플랫폼 주식 제어 결과 기록 실패 — command={} code={} (효과는 있었을 수 있음, 재실행 없음)", request.command(), reply.code());
            unknown(claim);
            return pending("stock-control-unknown");
        }
        log.info("플랫폼 주식 제어 처리 — command={} outcome={} code={}", request.command(), result.outcome(), reply.code());
        return stored(value);
    }

    public Answer observe(byte[] body) {
        if (!wellFormed(body, PROOF_FIELDS)) return error(400, "stock-control-malformed");
        StockControlVerifier.Verified value;
        try {
            value = verifier.verify(body);
        } catch (SecurityException denied) {
            return error(403, "stock-control-proof-denied");
        }
        lock.lock();
        try {
            try {
                if (receipts.find(value).isEmpty()) return error(404, "stock-control-not-received");
            } catch (SecurityException conflict) {
                return error(409, "stock-control-update-conflict");
            }
            try {
                attempts.recover(value);
            } catch (IllegalStateException none) {
                if ("stock-control-attempt-not-found".equals(none.getMessage())) return pending("stock-control-received");
                throw none;
            }
            return stored(value);
        } catch (SecurityException denied) {
            return error(403, "stock-control-proof-denied");
        } catch (IllegalStateException unavailable) {
            return error(503, "stock-control-unavailable");
        } finally {
            lock.unlock();
        }
    }

    public Answer ack(byte[] body) {
        if (!wellFormed(body, ACK_FIELDS)) return error(400, "stock-result-ack-malformed");
        StockControlResultAckVerifier.Verified value;
        try {
            value = ackVerifier.verify(body);
        } catch (SecurityException denied) {
            return error(403, "stock-result-ack-denied");
        }
        try {
            acks.record(value);
            return status(200, "stock-result-ack-recorded", "result", "recorded");
        } catch (SecurityException denied) {
            return error(403, "stock-result-ack-denied");
        } catch (IllegalStateException failed) {
            return "stock-result-ack-conflict".equals(failed.getMessage())
                    ? error(409, "stock-result-ack-conflict") : error(503, "stock-control-unavailable");
        }
    }

    /** Stored terminal result as a fresh 137 proof bound to the original request fingerprint; otherwise 202. */
    private Answer stored(StockControlVerifier.Verified value) {
        StockControlAttemptStore.ResultSnapshot result;
        try {
            result = attempts.result(value);
        } catch (SecurityException denied) {
            return error(403, "stock-control-proof-denied");
        }
        if (result == null) return pending("stock-control-pending");
        try {
            return new Answer(200, "stock-control-result", issuer.issue(value));
        } catch (SecurityException expired) {
            return pending("stock-control-result-recorded");
        }
    }

    private void unknown(StockControlAttemptStore.Claim claim) {
        try {
            attempts.markUnknown(claim);
        } catch (RuntimeException ignored) {
            // recover() later turns an expired running attempt into unknown.
        }
    }

    static boolean wellFormed(byte[] body, Set<String> fields) {
        try {
            if (body == null || body.length == 0 || body.length > MAX_BODY) return false;
            JsonNode root = JSON.readTree(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString());
            if (root == null || !root.isObject()) return false;
            var names = new HashSet<String>();
            root.fieldNames().forEachRemaining(names::add);
            if (!names.equals(fields)) return false;
            for (String name : fields) if (!root.get(name).isTextual()) return false;
            return true;
        } catch (Exception malformed) {
            return false;
        }
    }

    private static Answer pending(String code) {
        return status(202, code, "state", code);
    }

    static Answer error(int status, String code) {
        return status(status, code, "error", code);
    }

    private static Answer status(int status, String code, String field, String value) {
        var node = JSON.createObjectNode();
        node.put("contract_version", "1.0");
        node.put(field, value);
        try {
            return new Answer(status, code, JSON.writeValueAsBytes(node));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

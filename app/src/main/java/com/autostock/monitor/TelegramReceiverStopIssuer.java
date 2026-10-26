package com.autostock.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.EdECPrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Signs only the currently active durable stop. Explicit construction; no bean, key loader or endpoint. */
public final class TelegramReceiverStopIssuer {
    public static final String PURPOSE = "autostock-telegram-receiver-stopped-v1";
    private final TelegramReceiverStopStore store;
    private final String kid, bot;
    private final PrivateKey key;
    private final Clock clock;

    public TelegramReceiverStopIssuer(TelegramReceiverStopStore store, String kid, PrivateKey key, String botRef, Clock clock) {
        if (store == null || kid == null || !kid.matches("[A-Za-z0-9_-]{1,80}") || !(key instanceof EdECPrivateKey ed) || !ed.getParams().getName().equals("Ed25519")
                || botRef == null || !botRef.matches("[a-f0-9]{64}") || clock == null) throw new IllegalArgumentException("invalid-receiver-stop-issuer");
        this.store = store; this.kid = kid; this.key = key; this.bot = botRef; this.clock = clock;
    }

    public byte[] issue() {
        var stop = store.active().orElseThrow(TelegramReceiverStopIssuer::denied);
        Instant issued = clock.instant(), expires = issued.plusSeconds(30);
        if (stop.recordedAt().isAfter(issued)) throw denied();
        try {
            var json = new ObjectMapper();
            var statement = json.createObjectNode();
            statement.put("contract_version", "1.0"); statement.put("app_id", "autostock"); statement.put("bot_ref", bot);
            statement.put("stop_id", stop.stopId().toString()); statement.put("confirmed_next_offset", Long.toString(stop.confirmedNextOffset()));
            statement.put("pending_resume_dropped", stop.pendingResumeDropped()); statement.put("instance_id", stop.instanceId()); statement.put("stopped_at", stop.recordedAt().toString());
            String encoded = Base64.getEncoder().encodeToString(json.writeValueAsBytes(statement));
            var bytes = new ByteArrayOutputStream(); var framed = new DataOutputStream(bytes);
            for (String field : List.of(PURPOSE, kid, issued.toString(), expires.toString(), encoded)) { byte[] utf8 = field.getBytes(StandardCharsets.UTF_8); framed.writeInt(utf8.length); framed.write(utf8); }
            var signature = Signature.getInstance("Ed25519"); signature.initSign(key); signature.update(bytes.toByteArray());
            byte[] envelope = json.writeValueAsBytes(Map.of("contract_version", "1.0", "key_id", kid, "issued_at", issued.toString(), "expires_at", expires.toString(),
                    "statement_base64", encoded, "signature", Base64.getEncoder().encodeToString(signature.sign())));
            var still = store.active();
            if (still.isEmpty() || !still.get().equals(stop) || !clock.instant().isBefore(expires)) throw denied();
            return envelope;
        } catch (GeneralSecurityException | IOException invalid) {
            throw denied();
        }
    }

    private static SecurityException denied() { return new SecurityException("receiver-stop-issue-denied"); }
}

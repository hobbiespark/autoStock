package com.autostock.monitor;

import com.autostock.support.PostgresDataJpaTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class TelegramReceiverStopIssuerDbTest extends PostgresDataJpaTest {
    @Autowired DataSource source;
    TelegramReceiverStopStore store;
    final ObjectMapper json = new ObjectMapper();
    static final String BOT = "c".repeat(64);

    @BeforeEach void setUp() {
        store = new TelegramReceiverStopStore(source);
        store.active().ifPresent(s -> store.withdraw(s.stopId(), "test-cleanup"));
    }

    @Test void signsOnlyActiveStopAndExportsPublicFixture() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var clock = Clock.fixed(Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(5), ZoneOffset.UTC);
        var issuer = new TelegramReceiverStopIssuer(store, "autostock_stop_test", pair.getPrivate(), BOT, clock);
        assertEquals("receiver-stop-issue-denied", assertThrows(SecurityException.class, issuer::issue).getMessage());
        var stop = store.recordStopped(43, true, "pc-1");
        byte[] envelope = issuer.issue();
        var outer = json.readTree(envelope);
        var names = new HashSet<String>(); outer.fieldNames().forEachRemaining(names::add);
        assertEquals(Set.of("contract_version", "key_id", "issued_at", "expires_at", "statement_base64", "signature"), names);
        assertEquals(clock.instant().plusSeconds(30).toString(), outer.get("expires_at").textValue());
        var bytes = new ByteArrayOutputStream(); var framed = new DataOutputStream(bytes);
        for (String field : List.of("autostock-telegram-receiver-stopped-v1", "autostock_stop_test", outer.get("issued_at").textValue(), outer.get("expires_at").textValue(), outer.get("statement_base64").textValue())) {
            byte[] utf8 = field.getBytes(StandardCharsets.UTF_8); framed.writeInt(utf8.length); framed.write(utf8);
        }
        var check = Signature.getInstance("Ed25519"); check.initVerify(pair.getPublic()); check.update(bytes.toByteArray());
        assertTrue(check.verify(Base64.getDecoder().decode(outer.get("signature").textValue())));
        var statement = json.readTree(Base64.getDecoder().decode(outer.get("statement_base64").textValue()));
        assertEquals(8, statement.size());
        assertEquals(stop.stopId().toString(), statement.get("stop_id").textValue());
        assertEquals("43", statement.get("confirmed_next_offset").textValue());
        assertTrue(statement.get("pending_resume_dropped").booleanValue());
        assertEquals(stop.recordedAt().toString(), statement.get("stopped_at").textValue());
        String directory = System.getenv("AUTOSTOCK_RECEIVER_STOP_INTEROP_DIR");
        if (directory != null && !directory.isBlank()) {
            Path root = Path.of(directory); Files.createDirectories(root);
            Files.write(root.resolve("stopped.json"), envelope);
            Files.write(root.resolve("public-metadata.json"), json.writeValueAsBytes(Map.of("public_key", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                    "key_id", "autostock_stop_test", "now", clock.instant().toString(), "bot_ref", BOT, "stop_id", stop.stopId().toString(), "stopped_at", stop.recordedAt().toString())));
        }
        store.withdraw(stop.stopId(), "pc-1");
        assertThrows(SecurityException.class, issuer::issue, "withdrawn stop cannot be signed");
        var early = new TelegramReceiverStopIssuer(store, "autostock_stop_test", pair.getPrivate(), BOT, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        var again = store.recordStopped(44, false, "pc-1");
        assertThrows(SecurityException.class, early::issue, "clock before durable stop cannot sign");
        store.withdraw(again.stopId(), "pc-1");
        assertThrows(IllegalArgumentException.class, () -> new TelegramReceiverStopIssuer(store, "k", pair.getPrivate(), "bad", clock));
    }
}

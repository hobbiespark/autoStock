package com.autostock.monitor;

import com.autostock.monitor.control.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;

/**
 * Opt-in only (P2-2d): without {@code autostock.platform.stock-control.enabled=true} nothing here exists, so no port is
 * bound and no key file is read. When enabled every setting is explicit — dedicated port, pinned bot/space/actor scope,
 * the platform's request-proof and ACK public keys, this app's result-signing key and the ledger encryption key — and any
 * rejection stops startup. No default port, key, fallback or generation. Keys are created only in G2 (VALUNIVO 143).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "autostock.platform.stock-control", name = "enabled", havingValue = "true")
public class PlatformStockControlConfiguration {
    static final String PREFIX = "autostock.platform.stock-control.";

    @Bean(destroyMethod = "close")
    PlatformStockControlListener platformStockControlListener(Environment environment, DataSource source, Clock clock,
                                                              TelegramStockCommands commands) throws IOException {
        int port = port(environment);
        String bot = required(environment, "bot-ref"), space = required(environment, "space-id"), actor = required(environment, "actor-ref");
        String requestKid = required(environment, "platform-request-key-id"), ackKid = required(environment, "platform-ack-key-id");
        String resultKid = required(environment, "app-result-key-id"), storeKid = required(environment, "store-key-id");
        Path requestKey = path(environment, "platform-request-public-key-file"), ackKey = path(environment, "platform-ack-public-key-file");
        Path resultKey = path(environment, "app-result-private-key-file"), storeKey = path(environment, "store-key-file");
        Duration budget = budget(environment);

        var verifier = new StockControlVerifier(requestKid, StockControlKeyFile.ed25519Public(requestKey), bot, space, actor, clock);
        var ackVerifier = new StockControlResultAckVerifier(ackKid, StockControlKeyFile.ed25519Public(ackKey), bot, space, actor, clock);
        var signing = StockControlKeyFile.ed25519Private(resultKey);
        byte[] storage = StockControlKeyFile.aes256(storeKey);
        StockControlReceiptStore receipts;
        StockControlAttemptStore attempts;
        try {
            receipts = new StockControlReceiptStore(source, verifier, storeKid, storage);
            attempts = new StockControlAttemptStore(source, verifier, clock, budget, new StockControlResultCipher(storeKid, storage));
        } finally {
            Arrays.fill(storage, (byte) 0);
        }
        var entry = new PlatformStockControlEntry(verifier, receipts, attempts,
                new StockControlResultIssuer(attempts, verifier, resultKid, signing, clock), ackVerifier,
                new StockControlResultAckStore(source, ackVerifier), commands, source);
        var listener = new PlatformStockControlListener(entry, port);
        listener.start();
        return listener;
    }

    /** Explicit dedicated port only — never 0, privileged, the business web server or the operator listener port. */
    static int port(Environment environment) {
        String raw = environment.getProperty(PREFIX + "port");
        String business = environment.getProperty("server.port", "8080").trim();
        String operator = environment.getProperty(HandoverOperatorListenerConfiguration.PREFIX + "port");
        if (raw == null || !raw.matches("[0-9]{4,5}") || !business.matches("[0-9]{1,5}")) {
            throw new IllegalStateException("invalid-stock-control-port");
        }
        int port = Integer.parseInt(raw);
        if (port < 1024 || port > 65535 || port == Integer.parseInt(business) || raw.equals(operator == null ? null : operator.trim())) {
            throw new IllegalStateException("invalid-stock-control-port");
        }
        return port;
    }

    static Duration budget(Environment environment) {
        String raw = environment.getProperty(PREFIX + "attempt-budget-seconds", "10").trim();
        if (!raw.matches("[0-9]{1,2}") || Integer.parseInt(raw) < 1 || Integer.parseInt(raw) > 30) {
            throw new IllegalStateException("invalid-stock-control-attempt-budget");
        }
        return Duration.ofSeconds(Integer.parseInt(raw));
    }

    private static String required(Environment environment, String name) {
        String value = environment.getProperty(PREFIX + name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("stock-control-settings-required");
        }
        return value.trim();
    }

    private static Path path(Environment environment, String name) {
        try {
            return Path.of(required(environment, name));
        } catch (InvalidPathException invalid) {
            throw new IllegalStateException("stock-control-key-file-rejected");
        }
    }
}

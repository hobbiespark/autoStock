package com.autostock.monitor;

import com.autostock.monitor.control.HandoverOperatorKeyFile;
import com.autostock.monitor.control.LegacyResumeGate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;

/**
 * Opt-in only (P2-2d): without {@code autostock.telegram.handover.local-resume.enabled=true} nothing here exists — no port,
 * no key read. When enabled it needs the handover stop store and the poller, an explicit dedicated port and its own key:
 * the key ID and file must differ from the platform-facing operator listener's, so the platform never holds a key that can
 * resume. Same key file format as {@link HandoverOperatorKeyFile} (lowercase hex 64).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "autostock.telegram.handover.local-resume", name = "enabled", havingValue = "true")
public class LegacyResumeConfiguration {
    static final String PREFIX = "autostock.telegram.handover.local-resume.";

    @Bean(destroyMethod = "close")
    LegacyResumeListener legacyResumeListener(Environment environment, DataSource source, Clock clock,
                                              ObjectProvider<TelegramReceiverStopStore> stopStore,
                                              ObjectProvider<TelegramCommandPoller> commandPoller) throws IOException {
        var stops = stopStore.getIfAvailable();
        var poller = commandPoller.getIfAvailable();
        if (stops == null || poller == null) {
            throw new IllegalStateException("local-resume-requires-telegram-handover");
        }
        int port = port(environment);
        String keyId = environment.getProperty(PREFIX + "key-id");
        String instanceId = environment.getProperty(PREFIX + "instance-id");
        String keyFile = environment.getProperty(PREFIX + "key-file");
        if (keyId == null || instanceId == null || keyFile == null || keyFile.isBlank()) {
            throw new IllegalStateException("local-resume-settings-required");
        }
        String operatorKeyId = environment.getProperty(HandoverOperatorListenerConfiguration.PREFIX + "key-id");
        String operatorKeyFile = environment.getProperty(HandoverOperatorListenerConfiguration.PREFIX + "key-file");
        if (keyId.equals(operatorKeyId) || (operatorKeyFile != null && samePath(keyFile, operatorKeyFile))) {
            throw new IllegalStateException("local-resume-key-must-differ-from-operator-listener");
        }
        Path path;
        try {
            path = Path.of(keyFile);
        } catch (InvalidPathException invalid) {
            throw new IllegalStateException("operator-key-file-rejected");
        }
        byte[] key = HandoverOperatorKeyFile.read(path);
        LegacyResumeGate gate;
        try {
            gate = new LegacyResumeGate(keyId, key, instanceId, clock);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
        var listener = new LegacyResumeListener(gate, new LegacyResumeOperator(gate, source, stops, poller), port);
        listener.start();
        return listener;
    }

    /** Explicit dedicated port: not privileged, not the business, operator-listener or stock-control port. */
    static int port(Environment environment) {
        String raw = environment.getProperty(PREFIX + "port");
        if (raw == null || !raw.matches("[0-9]{4,5}")) {
            throw new IllegalStateException("invalid-local-resume-port");
        }
        int port = Integer.parseInt(raw);
        String business = environment.getProperty("server.port", "8080").trim();
        if (port < 1024 || port > 65535 || !business.matches("[0-9]{1,5}") || port == Integer.parseInt(business)) {
            throw new IllegalStateException("invalid-local-resume-port");
        }
        for (String other : new String[]{HandoverOperatorListenerConfiguration.PREFIX + "port", PlatformStockControlConfiguration.PREFIX + "port"}) {
            String value = environment.getProperty(other);
            if (value != null && value.trim().equals(raw)) {
                throw new IllegalStateException("invalid-local-resume-port");
            }
        }
        return port;
    }

    private static boolean samePath(String a, String b) {
        try {
            return Path.of(a).toAbsolutePath().normalize().equals(Path.of(b).toAbsolutePath().normalize());
        } catch (InvalidPathException invalid) {
            return false;
        }
    }
}

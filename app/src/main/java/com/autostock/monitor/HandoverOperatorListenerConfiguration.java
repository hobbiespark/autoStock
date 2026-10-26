package com.autostock.monitor;

import com.autostock.monitor.control.HandoverOperatorAttemptStore;
import com.autostock.monitor.control.HandoverOperatorGate;
import com.autostock.monitor.control.HandoverOperatorKeyFile;
import com.autostock.monitor.control.HandoverOperatorRequestStore;
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
 * Opt-in only: without {@code autostock.telegram.handover.operator-listener.enabled=true} nothing here exists,
 * so no port is bound and no key file is read. When enabled, every setting is explicit (no default port, key,
 * fallback or generation) and any rejection stops startup. Requires the handover stop store and the poller.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "autostock.telegram.handover.operator-listener", name = "enabled", havingValue = "true")
public class HandoverOperatorListenerConfiguration {
    static final String PREFIX = "autostock.telegram.handover.operator-listener.";

    @Bean(destroyMethod = "close")
    HandoverOperatorListener handoverOperatorListener(Environment environment, DataSource source, Clock clock,
                                                      ObjectProvider<TelegramReceiverStopStore> stopStore,
                                                      ObjectProvider<TelegramCommandPoller> commandPoller) throws IOException {
        var stops = stopStore.getIfAvailable();
        var poller = commandPoller.getIfAvailable();
        if (stops == null || poller == null) {
            throw new IllegalStateException("operator-listener-requires-telegram-handover");
        }
        int port = port(environment);
        String keyId = environment.getProperty(PREFIX + "key-id");
        String instanceId = environment.getProperty(PREFIX + "instance-id");
        String keyFile = environment.getProperty(PREFIX + "key-file");
        if (keyId == null || instanceId == null || keyFile == null || keyFile.isBlank()) {
            throw new IllegalStateException("operator-listener-settings-required");
        }
        Path path;
        try {
            path = Path.of(keyFile);
        } catch (InvalidPathException invalid) {
            throw new IllegalStateException("operator-key-file-rejected");
        }
        byte[] key = HandoverOperatorKeyFile.read(path);
        HandoverOperatorGate gate;
        try {
            gate = new HandoverOperatorGate(keyId, key, instanceId, clock);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
        var coordinator = new OperatorHandoverCoordinator(gate, new HandoverOperatorRequestStore(source, gate),
                new HandoverOperatorAttemptStore(source, gate, clock), stops, poller);
        var listener = new HandoverOperatorListener(gate, coordinator, stops, port);
        listener.start();
        return listener;
    }

    /** Explicit dedicated port only — never 0, never a privileged port, never the business web server port. */
    static int port(Environment environment) {
        String raw = environment.getProperty(PREFIX + "port");
        String business = environment.getProperty("server.port", "8080");
        if (raw == null || !raw.matches("[0-9]{4,5}")) {
            throw new IllegalStateException("invalid-operator-listener-port");
        }
        int port = Integer.parseInt(raw);
        if (port < 1024 || port > 65535 || !business.trim().matches("[0-9]{1,5}") || port == Integer.parseInt(business.trim())) {
            throw new IllegalStateException("invalid-operator-listener-port");
        }
        return port;
    }
}

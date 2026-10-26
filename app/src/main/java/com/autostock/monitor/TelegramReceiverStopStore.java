package com.autostock.monitor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable handover stop for the single Telegram receiver (VALUNIVO R16). Opt-in: without
 * {@code autostock.telegram.handover.enabled=true} this bean is absent and the poller behaves exactly as before.
 * Rows are append-only; an active stop blocks polling across restarts until it is withdrawn.
 */
@Component
@ConditionalOnProperty(prefix = "autostock.telegram.handover", name = "enabled", havingValue = "true")
public class TelegramReceiverStopStore {
    public record Stop(UUID stopId, long confirmedNextOffset, boolean pendingResumeDropped, String instanceId, Instant recordedAt) {}

    private final DataSource source;

    public TelegramReceiverStopStore(DataSource source) {
        this.source = java.util.Objects.requireNonNull(source);
    }

    public Optional<Stop> active() {
        try (var c = source.getConnection()) {
            return active(c);
        } catch (SQLException e) {
            throw new IllegalStateException("telegram-receiver-stop-unavailable", e);
        }
    }

    public Stop recordStopped(long confirmedNextOffset, boolean pendingResumeDropped, String instanceId) {
        if (confirmedNextOffset < 0 || confirmedNextOffset == Long.MAX_VALUE) throw new IllegalArgumentException("invalid-receiver-offset");
        instance(instanceId);
        return write(c -> {
            if (active(c).isPresent()) throw new IllegalStateException("telegram-receiver-already-stopped");
            UUID id = UUID.randomUUID();
            try (var q = c.prepareStatement("insert into telegram_receiver_stop(stop_id,kind,confirmed_next_offset,pending_resume_dropped,instance_id) values (?,'stopped',?,?,?)")) {
                q.setObject(1, id); q.setLong(2, confirmedNextOffset); q.setBoolean(3, pendingResumeDropped); q.setString(4, instanceId); q.executeUpdate();
            }
            return active(c).orElseThrow();
        });
    }

    /** Only the stop that is currently active can be withdrawn. Never deletes the original row. */
    public void withdraw(UUID stopId, String instanceId) {
        instance(instanceId);
        write(c -> {
            var current = active(c);
            if (current.isEmpty() || !current.get().stopId().equals(stopId)) throw new IllegalStateException("telegram-receiver-stop-not-active");
            try (var q = c.prepareStatement("insert into telegram_receiver_stop(stop_id,kind,instance_id) values (?,'withdrawn',?)")) {
                q.setObject(1, stopId); q.setString(2, instanceId); q.executeUpdate();
            }
            return null;
        });
    }

    private Optional<Stop> active(Connection c) throws SQLException {
        try (var q = c.prepareStatement("select s.stop_id,s.confirmed_next_offset,s.pending_resume_dropped,s.instance_id,s.recorded_at from telegram_receiver_stop s"
                + " where s.kind='stopped' and not exists(select 1 from telegram_receiver_stop w where w.stop_id=s.stop_id and w.kind='withdrawn') order by s.seq desc");
             var rows = q.executeQuery()) {
            if (!rows.next()) return Optional.empty();
            var stop = new Stop(rows.getObject(1, UUID.class), rows.getLong(2), rows.getBoolean(3), rows.getString(4), rows.getTimestamp(5).toInstant());
            if (rows.next()) throw new IllegalStateException("telegram-receiver-stop-ambiguous");
            return Optional.of(stop);
        }
    }

    @FunctionalInterface private interface Work<T> { T apply(Connection c) throws SQLException; }

    private <T> T write(Work<T> work) {
        try (var c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (var q = c.prepareStatement("select pg_advisory_xact_lock(hashtextextended('autostock-telegram-receiver-stop',0))")) { q.execute(); }
                T result = work.apply(c); c.commit(); return result;
            } catch (RuntimeException | SQLException e) {
                c.rollback();
                if (e instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("telegram-receiver-stop-unavailable", e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("telegram-receiver-stop-unavailable", e);
        }
    }

    private static void instance(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_.:-]{1,80}")) throw new IllegalArgumentException("invalid-instance-id");
    }
}

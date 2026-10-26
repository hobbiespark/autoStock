package com.autostock.monitor;

import com.autostock.monitor.control.LegacyResumeGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Local operator resume of the legacy Telegram poller after the platform returned the receiver (P2-2d, user decision
 * 2026-10-27, alternative A to VALUNIVO 161 §5-1; 143 "legacy polling 재개 조건"). Not reachable from the platform-facing
 * operator listener, which stays stop/observe only (decision 2026-10-26).
 *
 * <p>Every authenticated request leaves one ledger row. It resumes only when the presented platform release evidence
 * (stop_id, lease returned, run released, fence) matches this app's active 142 stop row and the 158 request linked to it,
 * and the operator typed the confirmation for exactly that stop. The resume itself is the existing internal
 * {@link TelegramCommandPoller#resumeAfterWithdraw} meaning, with the next offset raised to the platform's next offset.
 * The evidence is copied by the operator and is not signed by the platform: the operator's confirmation and the local
 * key are the trust anchor (documented limitation).
 */
public final class LegacyResumeOperator {
    private static final Logger log = LoggerFactory.getLogger(LegacyResumeOperator.class);

    public record Outcome(int status, String result, String reason, UUID stopId, Long legacyNextOffset) {
        @Override public String toString() { return "LegacyResumeOutcome[status=" + status + ",reason=" + reason + "]"; }
    }

    private final LegacyResumeGate gate;
    private final DataSource source;
    private final TelegramReceiverStopStore stops;
    private final TelegramCommandPoller poller;

    public LegacyResumeOperator(LegacyResumeGate gate, DataSource source, TelegramReceiverStopStore stops, TelegramCommandPoller poller) {
        this.gate = Objects.requireNonNull(gate);
        this.source = Objects.requireNonNull(source);
        this.stops = Objects.requireNonNull(stops);
        this.poller = Objects.requireNonNull(poller);
    }

    public synchronized Outcome resume(LegacyResumeGate.Verified value) {
        gate.requireVerified(value);
        var request = value.request();
        var evidence = request.evidence();
        var existing = existing(request.requestId());
        if (existing.isPresent()) {
            var row = existing.get();
            if (!row.keyId().equals(value.keyId()) || !row.instanceId().equals(request.instanceId()) || !row.fingerprint().equals(value.fingerprint())) {
                return new Outcome(409, "error", "legacy-resume-request-id-conflict", null, null);
            }
            // Same request again: never executes twice; report the recorded decision.
            return new Outcome(409, "error", "legacy-resume-already-received-" + row.state(), row.stopId(), null);
        }
        String reason = check(request);
        if (reason != null) {
            insert(value, "rejected", reason);
            log.warn("로컬 운영자 legacy 수신 재개 거절 — reason={} request_id={}", reason, request.requestId());
            return new Outcome(409, "rejected", reason, evidence.stopId(), null);
        }
        insert(value, "received", null);
        long offset;
        try {
            offset = poller.resumeAfterPlatformRelease(evidence.stopId(), request.instanceId(), evidence.platformNextOffset());
        } catch (RuntimeException failed) {
            String code = failed.getMessage() != null && failed.getMessage().matches("[a-z0-9-]{1,80}") ? failed.getMessage() : "legacy-resume-failed";
            decide(request.requestId(), "failed", code, null);
            log.error("로컬 운영자 legacy 수신 재개 실패 — reason={} request_id={}", code, request.requestId());
            return new Outcome(503, "failed", code, evidence.stopId(), null);
        }
        decide(request.requestId(), "resumed", null, offset);
        log.warn("로컬 운영자 legacy 수신 재개 — stop_id={} platform_run_id={} fence={} legacy_next_offset={} request_id={}",
                evidence.stopId(), evidence.platformRunId(), evidence.platformFence(), offset, request.requestId());
        return new Outcome(200, "resumed", null, evidence.stopId(), offset);
    }

    /** First failing condition, or null when the evidence matches this app's own stop records. */
    private String check(LegacyResumeGate.Request request) {
        var e = request.evidence();
        if (e.stopId() == null || e.platformRunId() == null || e.platformStopRequestId() == null || e.platformRunState() == null
                || e.platformLeaseReturned() == null || e.platformNextOffset() == null || e.platformFence() == null) {
            return "release-evidence-missing";
        }
        if (!e.platformLeaseReturned()) return "platform-lease-not-returned";
        if (!"released".equals(e.platformRunState())) return "platform-run-not-released";
        if (e.platformFence() < 1) return "platform-fence-invalid";
        if (!LegacyResumeGate.confirmation(e.stopId()).equals(request.operatorConfirmation())) return "operator-confirmation-mismatch";
        Optional<TelegramReceiverStopStore.Stop> active;
        Optional<TelegramReceiverStopStore.OperatorStop> linked;
        try {
            active = stops.active();
            linked = stops.operatorStop(e.platformStopRequestId());
        } catch (RuntimeException unavailable) {
            return "legacy-stop-state-unavailable";
        }
        if (active.isEmpty()) return "legacy-stop-not-active";
        if (!active.get().stopId().equals(e.stopId())) return "stop-id-mismatch";
        if (linked.isEmpty() || !linked.get().active() || !linked.get().stop().stopId().equals(e.stopId())) return "platform-stop-request-mismatch";
        if (e.platformNextOffset() < active.get().confirmedNextOffset()) return "platform-offset-below-legacy-stop";
        return null;
    }

    private record Row(String keyId, String instanceId, String fingerprint, String state, UUID stopId) {}

    private Optional<Row> existing(UUID requestId) {
        try (var c = source.getConnection(); var q = c.prepareStatement(
                "select operator_key_id,instance_id,request_sha256,state,stop_id from telegram_legacy_resume_request where request_id=?")) {
            q.setObject(1, requestId);
            try (var rows = q.executeQuery()) {
                return rows.next() ? Optional.of(new Row(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getObject(5, UUID.class))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("legacy-resume-ledger-unavailable", e);
        }
    }

    private void insert(LegacyResumeGate.Verified value, String state, String reason) {
        var r = value.request();
        var e = r.evidence();
        try (var c = source.getConnection(); var q = c.prepareStatement("insert into telegram_legacy_resume_request(request_id,operator_key_id,instance_id,request_sha256,stop_id,"
                + "platform_run_id,platform_stop_request_id,platform_run_state,platform_lease_returned,platform_next_offset,platform_fence,state,reason,decided_at)"
                + " values (?,?,?,?,?,?,?,?,?,?,?,?,?," + ("rejected".equals(state) ? "clock_timestamp()" : "null") + ")")) {
            q.setObject(1, r.requestId());
            q.setString(2, value.keyId());
            q.setString(3, r.instanceId());
            q.setString(4, value.fingerprint());
            q.setObject(5, e.stopId());
            q.setObject(6, e.platformRunId());
            q.setObject(7, e.platformStopRequestId());
            q.setString(8, e.platformRunState());
            if (e.platformLeaseReturned() == null) q.setNull(9, Types.BOOLEAN); else q.setBoolean(9, e.platformLeaseReturned());
            if (e.platformNextOffset() == null) q.setNull(10, Types.BIGINT); else q.setLong(10, e.platformNextOffset());
            if (e.platformFence() == null) q.setNull(11, Types.BIGINT); else q.setLong(11, e.platformFence());
            q.setString(12, state);
            q.setString(13, reason);
            q.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("legacy-resume-ledger-unavailable", failure);
        }
    }

    private void decide(UUID requestId, String state, String reason, Long legacyNextOffset) {
        try (var c = source.getConnection(); var q = c.prepareStatement(
                "update telegram_legacy_resume_request set state=?,reason=?,legacy_next_offset=?,decided_at=clock_timestamp() where request_id=? and state='received'")) {
            q.setString(1, state);
            q.setString(2, reason);
            if (legacyNextOffset == null) q.setNull(3, Types.BIGINT); else q.setLong(3, legacyNextOffset);
            q.setObject(4, requestId);
            if (q.executeUpdate() != 1) throw new IllegalStateException("legacy-resume-ledger-unavailable");
        } catch (SQLException failure) {
            throw new IllegalStateException("legacy-resume-ledger-unavailable", failure);
        }
    }
}

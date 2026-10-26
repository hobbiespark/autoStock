package com.autostock.monitor;

import com.autostock.portfolio.PositionBook;
import com.autostock.risk.KillSwitch;
import com.autostock.risk.RiskStateStore;
import com.autostock.support.PostgresDataJpaTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** VALUNIVO R16 이관 정지 — 실제 D18 DB 기록과 poller 흐름. Bot API는 요청 URL을 기록하는 가짜 exchange(네트워크 없음). */
class TelegramReceiverHandoverDbTest extends PostgresDataJpaTest {
    @Autowired DataSource source;
    TelegramReceiverStopStore store;
    final List<String> urls = new CopyOnWriteArrayList<>();
    final List<String> notices = new CopyOnWriteArrayList<>();
    KillSwitch killSwitch;

    @BeforeEach void setUp() {
        store = new TelegramReceiverStopStore(source);
        store.active().ifPresent(s -> store.withdraw(s.stopId(), "test-cleanup"));
        killSwitch = new KillSwitch(e -> {}, mock(RiskStateStore.class), Clock.systemUTC());
    }

    static String update(long id, String text) {
        return "{\"update_id\":" + id + ",\"message\":{\"message_id\":1,\"chat\":{\"id\":123,\"type\":\"private\"},\"text\":\"" + text + "\"}}";
    }

    TelegramCommandPoller poller(Function<String, Mono<ClientResponse>> responder, boolean withStore) {
        var builder = WebClient.builder().exchangeFunction(request -> { urls.add(request.url().toString()); return responder.apply(request.url().toString()); });
        var poller = new TelegramCommandPoller(builder, new TelegramProperties(true, "test-bot", "123"), killSwitch, new PositionBook(), (level, message) -> notices.add(message), Clock.systemUTC());
        if (withStore) poller.receiverStopStore(store);
        return poller;
    }

    static Mono<ClientResponse> json(String body) {
        return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(body).build());
    }

    static Function<String, Mono<ClientResponse>> serving(String... updates) {
        return url -> json(url.contains("limit=1") ? "{\"ok\":true,\"result\":[]}" : "{\"ok\":true,\"result\":[" + String.join(",", updates) + "]}");
    }

    @Test void stopConfirmsOnlyProcessedUpdatesAndBlocksPollingAcrossRestartUntilWithdrawn() {
        var poller = poller(serving(update(41, "hello"), update(42, "/status")), true);
        poller.poll();
        var stop = poller.stopForHandover("pc-1");
        assertEquals(43, stop.confirmedNextOffset());
        assertFalse(stop.pendingResumeDropped());
        assertTrue(urls.get(1).contains("offset=43") && urls.get(1).contains("limit=1") && urls.get(1).contains("timeout=0"), urls.toString());
        assertFalse(urls.get(1).contains("allowed_updates"));
        int calls = urls.size();
        poller.poll();
        assertEquals(calls, urls.size(), "stopped receiver must not poll");
        var restarted = poller(serving(), true);
        restarted.poll();
        assertEquals(calls, urls.size(), "active stop blocks a restarted poller");
        assertEquals("telegram-receiver-already-stopped", assertThrows(IllegalStateException.class, () -> restarted.stopForHandover("pc-2")).getMessage());
        restarted.resumeAfterWithdraw(stop.stopId(), "pc-2");
        restarted.poll();
        assertEquals(calls + 1, urls.size());
        assertTrue(store.active().isEmpty());
    }

    @Test void pendingResumeCodeIsDroppedAndCannotReleaseAfterHandover() {
        var poller = poller(serving(update(1, "/stop"), update(2, "/resume")), true);
        poller.poll();
        assertTrue(killSwitch.isEngaged());
        String code = notices.stream().map(n -> java.util.regex.Pattern.compile("/resume (\\d{4})").matcher(n)).filter(java.util.regex.Matcher::find).map(m -> m.group(1)).findFirst().orElseThrow();
        var stop = poller.stopForHandover("pc-1");
        assertTrue(stop.pendingResumeDropped());
        poller.resumeAfterWithdraw(stop.stopId(), "pc-1");
        poller.handleUpdate(new com.fasterxml.jackson.databind.ObjectMapper().convertValue(Map.of("update_id", 3, "message", Map.of("chat", Map.of("id", 123), "text", "/resume " + code)), Map.class));
        assertTrue(killSwitch.isEngaged(), "dropped confirmation code must not release the kill switch");
    }

    @Test void failedConfirmationLeavesReceiverRunningWithoutRecord() {
        for (var failure : List.<Function<String, Mono<ClientResponse>>>of(
                url -> url.contains("limit=1") ? Mono.just(ClientResponse.create(HttpStatus.CONFLICT).build()) : json("{\"ok\":true,\"result\":[]}"),
                url -> json(url.contains("limit=1") ? "{\"ok\":false}" : "{\"ok\":true,\"result\":[]}"))) {
            var poller = poller(failure, true);
            assertEquals("telegram-handover-stop-failed", assertThrows(IllegalStateException.class, () -> poller.stopForHandover("pc-1")).getMessage());
            assertTrue(store.active().isEmpty());
            int calls = urls.size();
            poller.poll();
            assertEquals(calls + 1, urls.size(), "receiver keeps polling after a failed stop");
        }
    }

    @Test void stopWaitsForInFlightPollAndConfirmsAfterItsUpdates() throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var poller = poller(url -> {
            if (url.contains("limit=1")) return json("{\"ok\":true,\"result\":[]}");
            started.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return json("{\"ok\":true,\"result\":[" + update(50, "hello") + "]}");
        }, true);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var polling = pool.submit(poller::poll);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var stopping = pool.submit(() -> poller.stopForHandover("pc-1"));
            Thread.sleep(200);
            assertFalse(stopping.isDone(), "stop must wait for the in-flight batch");
            release.countDown();
            polling.get(5, TimeUnit.SECONDS);
            assertEquals(51, stopping.get(5, TimeUnit.SECONDS).confirmedNextOffset());
            assertTrue(urls.get(urls.size() - 1).contains("offset=51"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void rejectedGuardPreservesPollingAndPendingResumeWithoutConfirmingOrRecording() {
        var poller = poller(serving(update(1, "/stop"), update(2, "/resume")), true);
        poller.poll();
        String code = notices.stream().map(n -> java.util.regex.Pattern.compile("/resume (\\d{4})").matcher(n)).filter(java.util.regex.Matcher::find).map(m -> m.group(1)).findFirst().orElseThrow();
        int calls = urls.size();
        assertThrows(SecurityException.class, () -> poller.stopForHandover("pc-1", () -> { throw new SecurityException("expired claim"); }));
        assertEquals(calls, urls.size());
        assertTrue(store.active().isEmpty());
        poller.handleUpdate(new com.fasterxml.jackson.databind.ObjectMapper().convertValue(Map.of("update_id", 3, "message", Map.of("chat", Map.of("id", 123), "text", "/resume " + code)), Map.class));
        assertFalse(killSwitch.isEngaged(), "rejected stop must not discard a still valid legacy confirmation");
        poller.poll();
        assertEquals(calls + 1, urls.size());
    }

    @Test void guardIsCheckedAfterInFlightRoundBeforeConfirmation() throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var stoppingStarted = new CountDownLatch(1);
        var valid = new java.util.concurrent.atomic.AtomicBoolean(true);
        var guardRan = new java.util.concurrent.atomic.AtomicBoolean(false);
        var poller = poller(url -> {
            if (!url.contains("limit=1")) {
                started.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }
            return json("{\"ok\":true,\"result\":[]}");
        }, true);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var polling = pool.submit(poller::poll);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var stopping = pool.submit(() -> { stoppingStarted.countDown(); return poller.stopForHandover("pc-1", () -> { guardRan.set(true); if (!valid.get()) throw new SecurityException("expired while waiting"); }); });
            try {
                assertTrue(stoppingStarted.await(5, TimeUnit.SECONDS));
                assertFalse(guardRan.get(), "guard must run after the polling lock");
                valid.set(false);
            } finally { release.countDown(); }
            polling.get(5, TimeUnit.SECONDS);
            assertInstanceOf(SecurityException.class, assertThrows(ExecutionException.class, () -> stopping.get(5, TimeUnit.SECONDS)).getCause());
            assertTrue(guardRan.get());
            assertEquals(1, urls.size(), "expired guard must not confirm an offset");
            assertTrue(store.active().isEmpty());
        } finally { release.countDown(); }
    }

    @Test void lostCommitResponseRechecksDurableStopBeforePollingAgain() {
        var poller = poller(serving(), true);
        assertThrows(IllegalStateException.class, () -> poller.stopForHandover("pc-1", () -> {}, (offset, dropped) -> {
            store.recordStopped(offset, dropped, "pc-1");
            throw new IllegalStateException("synthetic lost commit response");
        }));
        assertTrue(store.active().isPresent());
        int calls = urls.size();
        poller.poll();
        assertEquals(calls, urls.size(), "uncertain commit must not cache permission to resume polling");
    }

    @Test void disabledHandoverKeepsLegacyBehaviour() {
        var poller = poller(serving(update(7, "hello")), false);
        poller.poll();
        poller.poll();
        assertEquals(2, urls.size());
        assertEquals("telegram-handover-disabled", assertThrows(IllegalStateException.class, () -> poller.stopForHandover("pc-1")).getMessage());
    }

    @Test void stopRowsAreAppendOnlyAndSingleActive() throws Exception {
        var stop = store.recordStopped(10, false, "pc-1");
        assertEquals("telegram-receiver-already-stopped", assertThrows(IllegalStateException.class, () -> store.recordStopped(11, false, "pc-1")).getMessage());
        for (String sql : List.of("update telegram_receiver_stop set confirmed_next_offset=0", "delete from telegram_receiver_stop",
                "insert into telegram_receiver_stop(stop_id,kind,confirmed_next_offset,pending_resume_dropped,instance_id) values (gen_random_uuid(),'stopped',1,false,'x')",
                "insert into telegram_receiver_stop(stop_id,kind,instance_id) values (gen_random_uuid(),'withdrawn','x')")) {
            try (var c = source.getConnection(); var q = c.createStatement()) {
                assertEquals("42501", assertThrows(SQLException.class, () -> q.execute(sql)).getSQLState(), sql);
            }
        }
        assertThrows(IllegalStateException.class, () -> store.withdraw(UUID.randomUUID(), "pc-1"));
        store.withdraw(stop.stopId(), "pc-1");
        assertThrows(IllegalStateException.class, () -> store.withdraw(stop.stopId(), "pc-1"));
        var next = store.recordStopped(20, true, "pc-2");
        assertEquals(next, store.active().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> store.recordStopped(-1, false, "pc"));
        assertThrows(IllegalArgumentException.class, () -> store.recordStopped(1, false, "bad id"));
        store.withdraw(next.stopId(), "pc-2");
    }
}

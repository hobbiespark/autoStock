package com.autostock.risk;

import com.autostock.common.event.OrderSequenceRestored;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DailyLimitTracker — 일 주문 한도와 KST 자정 롤오버(UTC 15:00)를 주입된 시계로 검증한다.
 */
class DailyLimitTrackerTest {

    /** KST 09-29 23:59:59 = UTC 09-29 14:59:59. */
    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-29T14:59:59Z"));

    private DailyLimitTracker tracker(int dailyMaxOrders) {
        RiskProperties properties = mock(RiskProperties.class);
        when(properties.dailyMaxOrders()).thenReturn(dailyMaxOrders);
        return new DailyLimitTracker(properties, clock);
    }

    @Test
    void 한도까지는_허용하고_한도_다음_주문은_거부한다() {
        DailyLimitTracker tracker = tracker(2);

        assertEquals(1, tracker.tryAcquireOrderSlot().orElseThrow().serial());
        assertEquals(2, tracker.tryAcquireOrderSlot().orElseThrow().serial());
        assertTrue(tracker.tryAcquireOrderSlot().isEmpty());
        // 한도를 넘은 시도는 개수를 올리지 않는다 — 개수는 "오늘 실제로 쓴 슬롯 수"
        assertEquals(2, tracker.todayOrderCount());
    }

    @Test
    void 슬롯은_일련번호와_그_날짜를_함께_준다() {
        DailyLimitTracker tracker = tracker(5);

        DailyLimitTracker.OrderSlot slot = tracker.tryAcquireOrderSlot().orElseThrow();

        assertEquals(new DailyLimitTracker.OrderSlot(LocalDate.of(2026, 9, 29), 1), slot);
    }

    @Test
    void KST_자정이_지나면_카운터가_초기화된다() {
        DailyLimitTracker tracker = tracker(1);
        assertTrue(tracker.tryAcquireOrderSlot().isPresent());
        assertTrue(tracker.tryAcquireOrderSlot().isEmpty());

        clock.advance(Duration.ofSeconds(1)); // KST 09-30 00:00:00 (UTC 날짜는 아직 09-29)

        assertEquals(0, tracker.todayOrderCount());
        assertEquals(new DailyLimitTracker.OrderSlot(LocalDate.of(2026, 9, 30), 1),
                tracker.tryAcquireOrderSlot().orElseThrow());
    }

    @Test
    void UTC_자정은_KST_날짜_경계가_아니다() {
        clock.advance(Duration.ofHours(1)); // KST 09-30 01:00 시작
        DailyLimitTracker tracker = tracker(1);
        assertTrue(tracker.tryAcquireOrderSlot().isPresent());

        clock.advance(Duration.ofHours(9)); // UTC 09-30 00:00 = KST 09-30 10:00 — 같은 KST 날

        assertEquals(1, tracker.todayOrderCount());
        assertTrue(tracker.tryAcquireOrderSlot().isEmpty());
    }

    @Test
    void 여러_스레드가_동시에_슬롯을_얻어도_일련번호가_겹치지_않는다() throws Exception {
        // 예전 RiskGate는 증가 뒤 todayOrderCount()를 다시 읽어 번호로 썼다 — 두 스레드가 동시에 통과하면 같은 번호(BE-P1-3)
        int threads = 8;
        int perThread = 200;
        DailyLimitTracker tracker = tracker(threads * perThread);
        Set<Integer> serials = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicates = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        int serial = tracker.tryAcquireOrderSlot().orElseThrow().serial();
                        if (!serials.add(serial)) {
                            duplicates.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, duplicates.get());
        assertEquals(threads * perThread, serials.size());
        assertEquals(threads * perThread, tracker.todayOrderCount());
        assertTrue(tracker.tryAcquireOrderSlot().isEmpty(), "한도에 닿으면 더 주지 않는다");
    }

    @Test
    void 재기동_복원은_오늘_번호만_끌어올리고_다른_날은_무시한다() {
        DailyLimitTracker tracker = tracker(30);

        tracker.onOrderSequenceRestored(new OrderSequenceRestored(LocalDate.of(2026, 9, 28), 9));
        assertEquals(0, tracker.todayOrderCount());

        tracker.onOrderSequenceRestored(new OrderSequenceRestored(LocalDate.of(2026, 9, 29), 3));
        tracker.onOrderSequenceRestored(new OrderSequenceRestored(LocalDate.of(2026, 9, 29), 2)); // 중복·작은 값은 무해

        assertEquals(4, tracker.tryAcquireOrderSlot().orElseThrow().serial());
    }

    @Test
    void 시계가_뒤로_가도_날짜를_되돌려_번호를_1부터_다시_주지_않는다() {
        DailyLimitTracker tracker = tracker(30);
        clock.advance(Duration.ofSeconds(1)); // KST 09-30 00:00:00
        assertEquals(1, tracker.tryAcquireOrderSlot().orElseThrow().serial());

        clock.advance(Duration.ofSeconds(-2)); // 시계 보정으로 KST 09-29 23:59:59로 돌아감

        assertEquals(new DailyLimitTracker.OrderSlot(LocalDate.of(2026, 9, 30), 2),
                tracker.tryAcquireOrderSlot().orElseThrow());
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return zone;
                }

                @Override
                public Clock withZone(ZoneId z) {
                    return MutableClock.this.withZone(z);
                }

                @Override
                public Instant instant() {
                    return now;
                }
            };
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

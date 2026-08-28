package com.autostock.monitor;

import com.autostock.common.event.Fill;
import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Side;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SlippageTracker — 부호 규약(양수=불리), 수량 가중 평균, 결정가 미보유 체결 제외,
 * KST 자정 롤오버를 검증한다.
 */
class SlippageTrackerTest {

    /** 2026-08-28(금) 10:00 KST = 01:00 UTC. */
    private static final Instant DAY1 = Instant.parse("2026-08-28T01:00:00Z");

    private MutableClock clock;
    private SlippageTracker tracker;

    /** 테스트에서 시각을 진행시키기 위한 가변 Clock. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this; // instant()만 쓰이므로 존 변환은 무시해도 무방
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock(DAY1);
        tracker = new SlippageTracker(clock, new SimpleMeterRegistry());
    }

    private OrderRequest order(String key, Side side, String decisionPrice) {
        return new OrderRequest(key, "C3", "005930", side, 10,
                new BigDecimal(decisionPrice), DAY1);
    }

    private Fill fill(String key, Side side, long qty, String price) {
        return new Fill(key, "B1", "005930", side, qty, new BigDecimal(price), DAY1);
    }

    @Test
    void 매수는_결정가보다_비싸게_체결되면_양수_bps다() {
        tracker.onOrderRequest(order("O1", Side.BUY, "70000"));
        tracker.onFill(fill("O1", Side.BUY, 10, "70070"));

        // (70070-70000)/70000 = 0.001 = 10bps 불리
        var s = tracker.todaySummary();
        assertEquals(1, s.fills());
        assertEquals(10.0, s.avgBps(), 1e-9);
        assertEquals(10.0, s.maxBps(), 1e-9);
        assertEquals("005930", s.maxBpsSymbol());
    }

    @Test
    void 매도는_결정가보다_싸게_체결되면_양수_bps다() {
        tracker.onOrderRequest(order("O1", Side.SELL, "70000"));
        tracker.onFill(fill("O1", Side.SELL, 10, "69930"));

        // (70000-69930)/70000 = 10bps 불리
        assertEquals(10.0, tracker.todaySummary().avgBps(), 1e-9);
    }

    @Test
    void 유리한_체결은_음수_bps로_평균에_반영된다() {
        tracker.onOrderRequest(order("O1", Side.BUY, "70000"));
        tracker.onFill(fill("O1", Side.BUY, 10, "69930")); // -10bps (유리)

        var s = tracker.todaySummary();
        assertEquals(-10.0, s.avgBps(), 1e-9);
        assertEquals(0.0, s.maxBps(), 1e-9); // 최대 불리 폭은 갱신되지 않음
    }

    @Test
    void 평균은_체결_수량으로_가중된다() {
        tracker.onOrderRequest(order("O1", Side.BUY, "10000"));
        tracker.onOrderRequest(order("O2", Side.BUY, "10000"));
        tracker.onFill(fill("O1", Side.BUY, 30, "10010")); // 10bps × 30주
        tracker.onFill(fill("O2", Side.BUY, 10, "10050")); // 50bps × 10주

        // (10*30 + 50*10) / 40 = 800/40 = 20bps
        var s = tracker.todaySummary();
        assertEquals(2, s.fills());
        assertEquals(20.0, s.avgBps(), 1e-9);
        assertEquals(50.0, s.maxBps(), 1e-9);
    }

    @Test
    void 결정가_미보유_체결은_집계에서_제외된다() {
        // 재시작/Reconciliation 복구 체결 — 원 주문의 결정가를 모른다
        tracker.onFill(fill("UNKNOWN", Side.BUY, 10, "70070"));

        assertEquals(0, tracker.todaySummary().fills());
    }

    @Test
    void 부분체결_두_번은_각각_계측되고_두_번째는_제외된다() {
        // 현행 구현은 첫 Fill에서 결정가를 소진한다(remove) — 부분체결 2건 중
        // 두 번째는 제외됨을 명시한다. WS 실측 후 부분체결 빈도가 유의미하면
        // 잔여 수량 추적으로 확장한다 (TODO 실측).
        tracker.onOrderRequest(order("O1", Side.BUY, "70000"));
        tracker.onFill(fill("O1", Side.BUY, 5, "70070"));
        tracker.onFill(fill("O1", Side.BUY, 5, "70140"));

        assertEquals(1, tracker.todaySummary().fills());
    }

    @Test
    void KST_자정이_지나면_집계가_초기화된다() {
        tracker.onOrderRequest(order("O1", Side.BUY, "70000"));
        tracker.onFill(fill("O1", Side.BUY, 10, "70070"));
        assertEquals(1, tracker.todaySummary().fills());

        clock.instant = DAY1.plus(Duration.ofDays(1)); // 다음날 10:00 KST
        var s = tracker.todaySummary();
        assertEquals(0, s.fills());
        assertEquals(0.0, s.avgBps(), 1e-9);
    }
}

package com.autostock.risk;

import com.autostock.common.event.Fill;
import com.autostock.common.event.KillSwitchChanged;
import com.autostock.common.event.PositionRestored;
import com.autostock.common.event.Side;
import com.autostock.common.util.StockCode;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

/**
 * DailyPnlTracker — 실현손익 계산(비용 포함), 일 손실 한도 도달 시 킬스위치 작동,
 * KST 자정 롤오버를 검증한다.
 */
class DailyPnlTrackerTest {

    // risk.fee-rate=0.00015, risk.sell-tax-rate=0.0020 (CostModel과 동일값, 2026 세율)
    private static final double FEE_RATE = 0.00015;
    private static final double SELL_TAX_RATE = 0.0020;

    private final List<Object> killSwitchEvents = new ArrayList<>();
    private RiskProperties properties;
    private KillSwitch killSwitch;
    private EquitySource equitySource;
    private Clock clock;
    private RiskStateStore store;
    private DailyPnlTracker tracker;

    /** 2026-08-13(목) 10:00 KST = 01:00 UTC. */
    private static final Instant DAY1 = Instant.parse("2026-08-13T01:00:00Z");

    private Fill fill(Side side, long qty, String price, Instant at) {
        return new Fill("k", new BrokerOrderId("b"), new StockCode("005930"), side, new Quantity(qty), new Price(new BigDecimal(price)), at);
    }

    @BeforeEach
    void setUp() {
        // dailyMaxLossPct=-0.02, paperEquity=10,000,000 → 한도 = -200,000원
        properties = new RiskProperties(0.10, 5, -0.03, 0.05, -0.02, 30,
                10_000_000, FEE_RATE, SELL_TAX_RATE, false);
        killSwitch = new KillSwitch(killSwitchEvents::add, mock(RiskStateStore.class), Clock.systemUTC());
        equitySource = new PaperEquitySource(properties);
        clock = Clock.fixed(DAY1, ZoneOffset.UTC);
        store = mock(RiskStateStore.class);
        tracker = new DailyPnlTracker(properties, equitySource, killSwitch, store, clock);
    }

    @Test
    void 매수_매도_실현손익은_왕복비용을_뺀_값이다() {
        // 매수 10주 @70,000, 매도 10주 @71,000
        tracker.onFill(fill(Side.BUY, 10, "70000", DAY1));
        tracker.onFill(fill(Side.SELL, 10, "71000", DAY1));

        // 수작업 계산:
        //   grossPnl = (71000-70000)*10 = 10,000
        //   buyNotional = 70000*10 = 700,000, buyCost = 700,000*0.00015 = 105
        //   sellNotional = 71000*10 = 710,000, sellCost = 710,000*(0.00015+0.0020) = 710,000*0.00215 = 1526.5
        //   net = 10,000 - 105 - 1526.5 = 8,368.5
        BigDecimal expected = new BigDecimal("8368.5");
        assertEquals(0, expected.compareTo(tracker.todayRealizedPnl()));
    }

    @Test
    void 손실이_한도_도달하면_킬스위치가_작동한다() {
        // equity=10,000,000, 한도=-200,000원. 큰 폭 손실 매도로 한도를 넘긴다.
        tracker.onFill(fill(Side.BUY, 1000, "10000", DAY1));   // 매수 1000주 @10,000 = 1,000만원
        tracker.onFill(fill(Side.SELL, 1000, "9000", DAY1));   // 매도 1000주 @9,000 → 100만원 손실(한도 초과)

        assertTrue(killSwitch.isEngaged());
        assertEquals(1, killSwitchEvents.size());
        assertTrue(((KillSwitchChanged) killSwitchEvents.get(0)).reason().contains("일 손실 한도 도달"));
    }

    @Test
    void 한도_미만이면_킬스위치가_작동하지_않는다() {
        tracker.onFill(fill(Side.BUY, 10, "70000", DAY1));
        tracker.onFill(fill(Side.SELL, 10, "71000", DAY1)); // 이익 — 손실 아님

        assertFalse(killSwitch.isEngaged());
    }

    @Test
    void 자정이_지나면_실현손익이_리셋된다() {
        tracker.onFill(fill(Side.BUY, 10, "70000", DAY1));
        tracker.onFill(fill(Side.SELL, 10, "71000", DAY1));
        assertTrue(tracker.todayRealizedPnl().signum() > 0);

        // 다음날(KST 자정 이후)로 시계를 옮긴 새 트래커 대신, 같은 인스턴스의 clock을 다음날로
        // 바꿔치기할 수 없으므로(불변 필드) — 실제 자정 롤오버는 todayRealizedPnl() 호출 시점의
        // Clock을 기준으로 판단한다는 걸 별도 트래커로 검증한다.
        Clock nextDay = Clock.fixed(DAY1.plusSeconds(24 * 3600), ZoneOffset.UTC); // +1일
        DailyPnlTracker nextDayTracker = new DailyPnlTracker(properties, equitySource, killSwitch, mock(RiskStateStore.class), nextDay);
        // 전날 장부를 그대로 재현할 수는 없지만(별도 인스턴스), 새 날짜의 트래커는 0에서 시작함을 확인한다.
        assertEquals(0, BigDecimal.ZERO.compareTo(nextDayTracker.todayRealizedPnl()));
    }

    @Test
    void 같은_인스턴스도_시간이_지나면_롤오버된다() {
        // rollDayIfNeeded는 clock 조회 시점 기준이므로, Clock을 직접 바꿔 끼우는 대신
        // "하루 경과 후 새 Fill이 오면 리셋된 상태에서 다시 쌓인다"를 MutableClock으로 검증한다.
        MutableClock mutableClock = new MutableClock(DAY1, ZoneOffset.UTC);
        DailyPnlTracker t = new DailyPnlTracker(properties, equitySource, killSwitch, mock(RiskStateStore.class), mutableClock);

        t.onFill(fill(Side.BUY, 10, "70000", DAY1));
        t.onFill(fill(Side.SELL, 10, "71000", DAY1));
        assertTrue(t.todayRealizedPnl().signum() > 0);

        // 다음날 KST로 시계 이동
        mutableClock.setInstant(DAY1.plusSeconds(24 * 3600));
        assertEquals(0, BigDecimal.ZERO.compareTo(t.todayRealizedPnl()));
    }

    // ---- Phase 0.2: 재기동 복원 (aiDoc/risk-state-persistence.md) ----

    private static final LocalDate DAY1_KST = LocalDate.of(2026, 8, 13);

    @Test
    void 실현손익이_바뀔_때마다_오늘_누계를_저장한다() {
        tracker.onFill(fill(Side.BUY, 10, "70000", DAY1));
        tracker.onFill(fill(Side.SELL, 10, "71000", DAY1));

        verify(store).saveDailyPnl(eq(DAY1_KST), argThat(v -> v.compareTo(new BigDecimal("8368.5")) == 0), eq(DAY1));
    }

    @Test
    void 기동_시_오늘_저장값으로_누계를_복원하고_이어서_한도를_판단한다() {
        when(store.loadDailyPnl(DAY1_KST)).thenReturn(Optional.of(new BigDecimal("-150000")));

        tracker.restoreToday();
        assertEquals(0, new BigDecimal("-150000").compareTo(tracker.todayRealizedPnl()));
        assertFalse(killSwitch.isEngaged()); // 복원만으로는 켜지 않는다

        // 재기동 뒤 6만 원 손실 + 비용 → 누계 -21만 원대로 한도(-20만 원) 도달
        tracker.onFill(fill(Side.BUY, 100, "10000", DAY1));
        tracker.onFill(fill(Side.SELL, 100, "9400", DAY1));
        assertTrue(killSwitch.isEngaged());
    }

    @Test
    void 복원은_KST_오늘_날짜_행만_읽는다() {
        // 2026-08-13 15:30 UTC = 2026-08-14 00:30 KST — UTC 날짜(8/13) 행을 읽으면 어제 손실이 섞인다
        Clock afterKstMidnight = Clock.fixed(Instant.parse("2026-08-13T15:30:00Z"), ZoneOffset.UTC);
        DailyPnlTracker t = new DailyPnlTracker(properties, equitySource, killSwitch, store, afterKstMidnight);

        t.restoreToday();

        verify(store).loadDailyPnl(LocalDate.of(2026, 8, 14));
        assertEquals(0, BigDecimal.ZERO.compareTo(t.todayRealizedPnl()));
    }

    @Test
    void 복원한_누계가_한도를_넘었어도_킬스위치를_켜지_않는다() {
        // 한도 도달로 켜졌던 킬스위치는 그 자체가 복원된다 — 사람이 해제했다면 그 판단을 존중한다
        when(store.loadDailyPnl(DAY1_KST)).thenReturn(Optional.of(new BigDecimal("-300000")));

        tracker.restoreToday();

        assertFalse(killSwitch.isEngaged());
        assertTrue(killSwitchEvents.isEmpty());
    }

    @Test
    void 저장이_실패해도_누계와_한도_판단은_계속된다() {
        doThrow(new RuntimeException("DB 연결 끊김")).when(store).saveDailyPnl(any(), any(), any());

        tracker.onFill(fill(Side.BUY, 1000, "10000", DAY1));
        tracker.onFill(fill(Side.SELL, 1000, "9000", DAY1));

        assertTrue(tracker.todayRealizedPnl().signum() < 0);
        assertTrue(killSwitch.isEngaged());
    }

    @Test
    void 복원_실패시_0에서_시작한다() {
        when(store.loadDailyPnl(any())).thenThrow(new RuntimeException("DB 연결 끊김"));

        tracker.restoreToday();

        assertEquals(0, BigDecimal.ZERO.compareTo(tracker.todayRealizedPnl()));
    }

    @Test
    void 복원_포지션으로_원가_장부를_시드해_매도_실현손익을_계산한다() {
        tracker.onPositionRestored(new PositionRestored(new StockCode("005930"), 10, new Price(new BigDecimal("70000"))));

        tracker.onFill(fill(Side.SELL, 10, "71000", DAY1));

        assertEquals(0, new BigDecimal("8368.5").compareTo(tracker.todayRealizedPnl()));
    }

    @Test
    void 평단_미상_복원_포지션은_시드하지_않는다() {
        tracker.onPositionRestored(new PositionRestored(new StockCode("005930"), 10, null));

        tracker.onFill(fill(Side.SELL, 10, "71000", DAY1)); // 장부에 없는 종목 매도 — 계산 스킵(기존 동작)

        assertEquals(0, BigDecimal.ZERO.compareTo(tracker.todayRealizedPnl()));
    }

    @Test
    void 이미_체결로_만든_장부는_복원_포지션이_덮지_않는다() {
        tracker.onFill(fill(Side.BUY, 10, "70000", DAY1));
        tracker.onPositionRestored(new PositionRestored(new StockCode("005930"), 10, new Price(new BigDecimal("60000"))));

        tracker.onFill(fill(Side.SELL, 10, "71000", DAY1));

        assertEquals(0, new BigDecimal("8368.5").compareTo(tracker.todayRealizedPnl())); // 평단 70,000 기준
    }

    /** 테스트 전용 가변 Clock — 하루가 지난 것처럼 시각을 이동시켜 롤오버를 결정론적으로 검증한다. */
    private static final class MutableClock extends Clock {
        private Instant instant;
        private final java.time.ZoneId zone;

        MutableClock(Instant instant, java.time.ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public java.time.ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

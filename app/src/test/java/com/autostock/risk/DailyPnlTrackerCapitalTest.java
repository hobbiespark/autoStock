package com.autostock.risk;

import com.autostock.common.event.Fill;
import com.autostock.common.event.PositionRestored;
import com.autostock.common.event.Side;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import com.autostock.common.util.StockCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 일 손실 한도 기준(2026-10-21, 운용 자금) — 운용 자금이 켜져 있으면 min(계좌, 운용 자금)과 오늘 최대 보유액 중 큰 값.
 * 계좌 1억, 한도 −5%, 운용 자금 2,000만 원.
 */
class DailyPnlTrackerCapitalTest {

    private static final Instant DAY1 = Instant.parse("2026-08-13T01:00:00Z");   // 목 10:00 KST
    private static final StockCode SAMSUNG = new StockCode("005930");

    private RiskProperties properties;
    private KillSwitch killSwitch;

    @BeforeEach
    void setUp() {
        properties = new RiskProperties(0.10, 40, -0.03, 0.05, -0.05, 150, 100_000_000, 0.00015, 0.0020, false,
                1_000_000);
        killSwitch = new KillSwitch(event -> { }, mock(RiskStateStore.class), Clock.systemUTC());
    }

    private DailyPnlTracker tracker(TradingCapitalProperties capital) {
        return new DailyPnlTracker(properties, new PaperEquitySource(properties), killSwitch, mock(RiskStateStore.class),
                Clock.fixed(DAY1, ZoneOffset.UTC), capital);
    }

    private static Fill fill(Side side, long qty, String price) {
        return new Fill("k", new BrokerOrderId("b"), SAMSUNG, side, new Quantity(qty), new Price(new BigDecimal(price)), DAY1);
    }

    @Test
    void 운용_자금이_한도_기준이다() {
        DailyPnlTracker tracker = tracker(new TradingCapitalProperties(20_000_000, 2.0));   // 한도 −100만
        tracker.onFill(fill(Side.BUY, 100, "100000"));
        tracker.onFill(fill(Side.SELL, 100, "89000"));   // −110만 − 비용

        assertTrue(killSwitch.isEngaged());
    }

    @Test
    void 운용_자금이_꺼져_있으면_계좌_평가액이_기준이다() {
        DailyPnlTracker tracker = tracker(TradingCapitalProperties.none());               // 한도 −500만
        tracker.onFill(fill(Side.BUY, 100, "100000"));
        tracker.onFill(fill(Side.SELL, 100, "89000"));

        assertFalse(killSwitch.isEngaged());
    }

    @Test
    void 운용_자금보다_큰_예전_보유를_정리하는_날은_오늘_최대_보유액이_기준이다() {
        // 6,000만 원 보유(전일 종가 기준) → 한도 −300만. 운용 자금 기준(−100만)이었다면 정리 도중 킬스위치가 켜진다
        DailyPnlTracker tracker = tracker(new TradingCapitalProperties(20_000_000, 2.0));
        tracker.onPositionRestored(new PositionRestored(SAMSUNG, 600, new Price(new BigDecimal("100000")),
                new Price(new BigDecimal("100000"))));

        tracker.onFill(fill(Side.SELL, 600, "97000"));   // −180만 − 비용 약 13만

        assertFalse(killSwitch.isEngaged());
    }

    @Test
    void 최대_보유액_기준이어도_그보다_크게_잃으면_킬스위치가_켜진다() {
        DailyPnlTracker tracker = tracker(new TradingCapitalProperties(20_000_000, 2.0));
        tracker.onPositionRestored(new PositionRestored(SAMSUNG, 600, new Price(new BigDecimal("100000")),
                new Price(new BigDecimal("100000"))));

        tracker.onFill(fill(Side.SELL, 600, "94000"));   // −360만 − 비용

        assertTrue(killSwitch.isEngaged());
    }
}

package com.autostock.strategy;

import com.autostock.common.event.Candle;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.autostock.strategy.StrategyTestSupport.TODAY;
import static com.autostock.strategy.StrategyTestSupport.candle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 단타 변동성 돌파 판단식 — B안 필터와 ADR-7 익절·손절(가격 기준). */
class DayBreakoutMathTest {

    @Test
    void 준비값은_전일_봉_SMA_전일_양봉() {
        List<Candle> bars = StrategyTestSupport.flatThen("005930", 19, "100000",
                candle("005930", TODAY.minusDays(1), "100000", "104000", "99000", "102000"));
        List<BigDecimal> closes = bars.stream().map(Candle::close).collect(Collectors.toList());
        Candle prev = bars.get(bars.size() - 1);
        DayBreakoutMath.Setup s = DayBreakoutMath.setup(closes, prev.open(), prev.high(), prev.low(), 20);
        assertTrue(s.prevUp());
        assertEquals(0, new BigDecimal("100100").compareTo(s.smaClose()), "(19×100,000 + 102,000) ÷ 20");
        assertNull(DayBreakoutMath.setup(closes.subList(0, 10), prev.open(), prev.high(), prev.low(), 20),
                "봉이 모자라면 null");
    }

    @Test
    void 필터는_전일_양봉이고_시가가_SMA보다_높을_때만() {
        DayBreakoutMath.Setup up = new DayBreakoutMath.Setup(new BigDecimal("100"), new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("105"), new BigDecimal("100"));
        assertTrue(DayBreakoutMath.passesFilters(up, new BigDecimal("101")));
        assertFalse(DayBreakoutMath.passesFilters(up, new BigDecimal("100")), "시가 = SMA는 통과 못 한다");
        DayBreakoutMath.Setup down = new DayBreakoutMath.Setup(new BigDecimal("106"), new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("105"), new BigDecimal("100"));
        assertFalse(DayBreakoutMath.passesFilters(down, new BigDecimal("120")));
    }

    @Test
    void 목표가는_시가_더하기_k_곱하기_전일_변동폭() {
        DayBreakoutMath.Setup s = new DayBreakoutMath.Setup(new BigDecimal("100000"), new BigDecimal("104000"),
                new BigDecimal("99000"), new BigDecimal("102000"), new BigDecimal("100000"));
        assertEquals(0, new BigDecimal("103500").compareTo(DayBreakoutMath.target(s, new BigDecimal("101000"), 0.5)));
    }

    @Test
    void 익절_손절은_평단_대비_가격_기준() {
        BigDecimal avg = new BigDecimal("100000");
        assertTrue(DayBreakoutMath.hitsTakeProfit(new BigDecimal("101800"), avg, 0.018));
        assertFalse(DayBreakoutMath.hitsTakeProfit(new BigDecimal("101700"), avg, 0.018));
        assertTrue(DayBreakoutMath.hitsStopLoss(new BigDecimal("98500"), avg, 0.015));
        assertFalse(DayBreakoutMath.hitsStopLoss(new BigDecimal("98600"), avg, 0.015));
        assertFalse(DayBreakoutMath.hitsStopLoss(new BigDecimal("1"), null, 0.015), "평단 모르면 판단 안 함");
    }
}

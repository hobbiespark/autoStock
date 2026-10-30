package com.autostock.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 단타 → 스윙·중기 이월 판단(2026-10-30) — 손절 > 스윙 익절 > 중기 이탈 > 기간 만료 > 2차 > 3차 > 유지. */
class SwingCarryMathTest {

    private static final SwingCarryMath.Rules RULES = new SwingCarryMath.Rules(0.07, 0.05, 20, 60, 10, 0);

    /** 종가가 from에서 to로 고르게 움직이는 n봉(마지막 = 어제). */
    private static List<BigDecimal> bars(int n, double from, double to) {
        List<BigDecimal> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double c = from + (to - from) * i / Math.max(1, n - 1);
            list.add(BigDecimal.valueOf(Math.round(c)));
        }
        return list;
    }

    /** 이월 뒤 완료된 봉 수 — 시험에서는 그 수를 바로 쓴다. */
    private static long dayOf(List<BigDecimal> bars, int fromEnd) {
        return fromEnd;
    }

    @Test
    void 평단_대비_7퍼센트_이하면_손절한다() {
        List<BigDecimal> b = bars(80, 10000, 9300);
        SwingCarryMath.Decision d = SwingCarryMath.decide(SwingCarryMath.Stage.SWING, 1, new BigDecimal("10000"), b,
                dayOf(b, 1), RULES);
        assertEquals(SwingCarryMath.Action.SELL, d.action());
        assertTrue(d.reason().startsWith("손절"), d.reason());
    }

    @Test
    void 스윙_단계에서_5퍼센트_오르면_익절한다() {
        List<BigDecimal> b = bars(80, 9000, 10500);
        SwingCarryMath.Decision d = SwingCarryMath.decide(SwingCarryMath.Stage.SWING, 1, new BigDecimal("10000"), b,
                dayOf(b, 1), RULES);
        assertEquals(SwingCarryMath.Action.SELL, d.action());
        assertTrue(d.reason().startsWith("스윙 익절"), d.reason());
    }

    @Test
    void 상승_추세면_2차를_산다() {
        List<BigDecimal> b = bars(80, 9000, 10200);   // 종가 > SMA20, 평단 대비 +2%
        SwingCarryMath.Decision d = SwingCarryMath.decide(SwingCarryMath.Stage.SWING, 1, new BigDecimal("10000"), b,
                dayOf(b, 1), RULES);
        assertEquals(SwingCarryMath.Action.ADD, d.action());
        assertEquals(2, d.tranche());
    }

    @Test
    void 이차까지_사고_중기_추세면_3차를_산다() {
        List<BigDecimal> b = bars(80, 9000, 10200);   // SMA20 > SMA60, 종가 > SMA60
        SwingCarryMath.Decision d = SwingCarryMath.decide(SwingCarryMath.Stage.SWING, 2, new BigDecimal("10000"), b,
                dayOf(b, 1), RULES);
        assertEquals(SwingCarryMath.Action.ADD, d.action());
        assertEquals(3, d.tranche());
    }

    @Test
    void 하락_추세면_더_사지_않고_유지한다() {
        List<BigDecimal> b = bars(80, 10500, 9700);   // 종가 < SMA20, 평단 −3%(손절 전)
        SwingCarryMath.Decision d = SwingCarryMath.decide(SwingCarryMath.Stage.SWING, 1, new BigDecimal("10000"), b,
                dayOf(b, 1), RULES);
        assertEquals(SwingCarryMath.Action.HOLD, d.action(), d.reason());
    }

    @Test
    void 스윙_단계_10거래일이_지나면_판다() {
        List<BigDecimal> b = bars(80, 10500, 9700);
        SwingCarryMath.Decision d = SwingCarryMath.decide(SwingCarryMath.Stage.SWING, 1, new BigDecimal("10000"), b,
                dayOf(b, 10), RULES);   // 이월일 뒤 완료된 봉 10개
        assertEquals(SwingCarryMath.Action.SELL, d.action());
        assertTrue(d.reason().startsWith("보유 기간 만료"), d.reason());
    }

    @Test
    void 중기_단계는_기간_제한_없이_SMA60_이탈에서만_판다() {
        List<BigDecimal> up = bars(80, 9000, 10400);
        SwingCarryMath.Decision hold = SwingCarryMath.decide(SwingCarryMath.Stage.MID, 3, new BigDecimal("10000"), up,
                dayOf(up, 70), RULES);
        assertEquals(SwingCarryMath.Action.HOLD, hold.action(), hold.reason());
        List<BigDecimal> down = bars(80, 10600, 9800);
        SwingCarryMath.Decision sell = SwingCarryMath.decide(SwingCarryMath.Stage.MID, 3, new BigDecimal("10000"), down,
                dayOf(down, 5), RULES);
        assertEquals(SwingCarryMath.Action.SELL, sell.action());
        assertTrue(sell.reason().startsWith("중기 추세 이탈"), sell.reason());
    }

    @Test
    void 평단이나_일봉이_없으면_보류한다() {
        assertEquals(SwingCarryMath.Action.HOLD, SwingCarryMath.decide(SwingCarryMath.Stage.SWING, 1, null,
                bars(80, 1, 2), 0, RULES).action());
        assertEquals(SwingCarryMath.Action.HOLD, SwingCarryMath.decide(SwingCarryMath.Stage.SWING, 1,
                BigDecimal.TEN, List.of(), 0, RULES).action());
    }

    @Test
    void 비용_후_수익은_왕복_비용보다_올랐을_때다() {
        assertTrue(SwingCarryMath.profitableAfterCost(new BigDecimal("10023"), new BigDecimal("10000"), 0.0023));
        assertFalse(SwingCarryMath.profitableAfterCost(new BigDecimal("10022"), new BigDecimal("10000"), 0.0023));
        assertFalse(SwingCarryMath.profitableAfterCost(null, new BigDecimal("10000"), 0.0023));
    }
}

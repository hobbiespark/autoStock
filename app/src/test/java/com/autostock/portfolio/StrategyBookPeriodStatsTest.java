package com.autostock.portfolio;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 구간 전략별 청산 성과(2026-11-06) — 종목·매도일마다 청산 1건(부분 체결 합침), 매수·평단 미상 매도는 빼고, 매도 원가는
 * 매도 전 평단 × 수량. 자료 모양은 10/23 ON-CLOSE 실측(삼성E&A 17주 한 번, 파인엠텍 93주 여러 번 분할 매도).
 */
class StrategyBookPeriodStatsTest {

    private static final Instant DAY = Instant.parse("2026-10-23T00:00:30Z");   // KST 09:00:30

    private static StrategyFillEntity sell(String strategy, String symbol, long qty, String price, String avg, String pnl) {
        return new StrategyFillEntity(strategy, symbol, "SELL", qty, new BigDecimal(price), "id", new BigDecimal(avg),
                new BigDecimal(pnl), DAY);
    }

    @Test
    void 종목_매도일마다_청산_1건으로_센다() {
        List<StrategyFillEntity> rows = List.of(
                sell("ON-CLOSE", "028050", 17, "58100", "55400", "43635.18"),
                sell("ON-CLOSE", "441270", 40, "9800", "10110", "-12000.00"),
                sell("ON-CLOSE", "441270", 53, "9790", "10110", "-17000.00"),
                new StrategyFillEntity("ON-CLOSE", "441270", "BUY", 85, new BigDecimal("11150"), "id", null, null, DAY),
                new StrategyFillEntity("ON-CLOSE", "999999", "SELL", 1, new BigDecimal("1000"), "id", null, null, DAY),
                sell("VB-DAY", "032830", 1, "274000", "269500", "3870.48"));

        List<StrategyBook.PeriodStat> stats = StrategyBook.periodStats(rows);

        assertEquals(2, stats.size());
        StrategyBook.PeriodStat on = stats.get(0);
        assertEquals("ON-CLOSE", on.strategyId());
        assertEquals(2, on.exits(), "파인엠텍 두 번 매도는 청산 1건");
        assertEquals(1, on.wins());
        assertEquals(0, new BigDecimal("14635.18").compareTo(on.realizedPnl()));
        // 원가 = 17 × 55,400 + 93 × 10,110 = 941,800 + 940,230
        assertEquals(0, new BigDecimal("1882030").compareTo(on.exitCost()));
        StrategyBook.PeriodStat vb = stats.get(1);
        assertEquals("VB-DAY", vb.strategyId());
        assertEquals(1, vb.exits());
        assertEquals(1, vb.wins());
    }

    @Test
    void 매도가_없으면_빈_목록() {
        assertTrue(StrategyBook.periodStats(List.of()).isEmpty());
    }
}

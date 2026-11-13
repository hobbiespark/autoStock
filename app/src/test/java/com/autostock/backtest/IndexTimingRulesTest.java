package com.autostock.backtest;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 트랙 Z 규칙 고정(docs/research/trackz_preregistration_20261114.md 2장). */
class IndexTimingRulesTest {

    @Test
    void 할로윈은_11월부터_4월까지() {
        assertTrue(IndexTimingRules.halloweenHold(LocalDate.of(2020, 11, 2)));
        assertTrue(IndexTimingRules.halloweenHold(LocalDate.of(2021, 4, 30)));
        assertFalse(IndexTimingRules.halloweenHold(LocalDate.of(2021, 5, 3)));
        assertFalse(IndexTimingRules.halloweenHold(LocalDate.of(2021, 10, 29)));
    }

    @Test
    void 월말효과는_마지막_거래일과_첫_3거래일() {
        List<LocalDate> cal = List.of(
                LocalDate.of(2021, 1, 4), LocalDate.of(2021, 1, 5), LocalDate.of(2021, 1, 6), LocalDate.of(2021, 1, 7),
                LocalDate.of(2021, 1, 28), LocalDate.of(2021, 1, 29),
                LocalDate.of(2021, 2, 1), LocalDate.of(2021, 2, 2), LocalDate.of(2021, 2, 3), LocalDate.of(2021, 2, 4));
        assertTrue(IndexTimingRules.turnOfMonthHold(cal, 2));    // 1-6: 1월 셋째 거래일
        assertFalse(IndexTimingRules.turnOfMonthHold(cal, 3));   // 1-7: 넷째 거래일
        assertFalse(IndexTimingRules.turnOfMonthHold(cal, 4));   // 1-28: 마지막 전날
        assertTrue(IndexTimingRules.turnOfMonthHold(cal, 5));    // 1-29: 1월 마지막 거래일
        assertTrue(IndexTimingRules.turnOfMonthHold(cal, 6));    // 2-1
        assertTrue(IndexTimingRules.turnOfMonthHold(cal, 8));    // 2-3: 셋째 거래일
        assertFalse(IndexTimingRules.turnOfMonthHold(cal, 9));   // 2-4: 넷째 거래일(달력 끝이라 마지막도 아님)
    }

    @Test
    void 변동성관리는_최근_변동성이_평소보다_크면_비중을_줄이고_1을_넘지_않는다() {
        double[] c = new double[300];
        c[0] = 100;
        for (int i = 1; i < c.length; i++) {
            double r = i >= 279 ? (i % 2 == 0 ? 0.04 : -0.04) : (i % 2 == 0 ? 0.01 : -0.01);
            c[i] = c[i - 1] * (1 + r);
        }
        double calm = IndexTimingRules.volatilityManagedWeight(c, 270);
        double stormy = IndexTimingRules.volatilityManagedWeight(c, 299);
        assertEquals(1.0, calm, 1e-9, "최근 21일이 평소와 같으면 1(상한)");
        assertTrue(stormy < 0.25, "최근 21일 변동성이 4배면 분산비로 크게 줄어야 함: " + stormy);
    }

    @Test
    void Z3는_비금융_저점수만_빼고_시총비중() {
        FundamentalsTable.Fundamentals bad = new FundamentalsTable.Fundamentals(100, 100, 50, 40, 40, 50, 20, 20, 5, 30, 12, 10,
                100, 120, 40, 60, -5, -10, 5, -15);
        FundamentalsTable.Fundamentals bank = new FundamentalsTable.Fundamentals(100, 100, 90, 90, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, 5, 1, 10, 10, 10, 10, Double.NaN, Double.NaN, 3, -2, 2, 1);
        FundamentalsTable table = new FundamentalsTable(Map.of(2015, Map.of("BAD", bad, "BANK", bank)));
        var ctx = new CrossSectionalStrategy.FormationContext(LocalDate.of(2016, 6, 30),
                Map.of("BAD", 10.0, "BANK", 30.0, "NODATA", 60.0));
        Map<String, double[]> h = Map.of("BAD", new double[61], "BANK", new double[61], "NODATA", new double[61]);

        CrossSectionalStrategy z3 = FactorStrategies.capWeightedQualityFilter(table, 3);
        List<String> picked = z3.select(ctx, h);
        assertEquals(List.of("BANK", "NODATA"), picked);
        assertEquals(Map.of("BANK", 0.333333, "NODATA", 0.666667).keySet(),
                CrossSectionalBacktestRunner.normalizedWeights(picked, z3.targetWeights(ctx, picked)).keySet());
        assertEquals(1.0 / 3, CrossSectionalBacktestRunner.normalizedWeights(picked, z3.targetWeights(ctx, picked)).get("BANK"), 1e-12);
        assertEquals(List.of("BAD", "BANK", "NODATA"), FactorStrategies.capWeightedAll().select(ctx, h));
    }
}

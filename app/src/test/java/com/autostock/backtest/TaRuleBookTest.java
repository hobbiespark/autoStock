package com.autostock.backtest;

import com.autostock.common.event.Candle;
import com.autostock.strategy.DailyRuleBook;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 차트 기법 라이브 규칙 묶음 — 그림자 포지션이 백테스트 러너의 보유 판정과 같은가(백테스트=라이브 동형, 2026-10-16).
 */
class TaRuleBookTest {

    private static final List<Supplier<BacktestStrategy>> RULES = List.of(IchimokuCloudStrategy::new,
            BollingerSqueezeStrategy::new, SuperTrendStrategy::new, Rsi2MeanReversionStrategy::new,
            BreakoutRetestStrategy::new);

    /** 결정론적 무작위 보행 일봉(시가·고가·저가·종가 일관). */
    static List<Candle> walk(long seed, int days) {
        Random r = new Random(seed);
        List<Candle> out = new ArrayList<>();
        double close = 50_000;
        LocalDate day = LocalDate.of(2024, 1, 2);
        for (int i = 0; i < days; i++) {
            double open = close * (1 + r.nextGaussian() * 0.01);
            double next = open * (1 + r.nextGaussian() * 0.02 + 0.0004);
            double high = Math.max(open, next) * (1 + Math.abs(r.nextGaussian()) * 0.008);
            double low = Math.min(open, next) * (1 - Math.abs(r.nextGaussian()) * 0.008);
            out.add(new Candle("005930", day.plusDays(i), bd(open), bd(high), bd(low), bd(next), 1000L));
            close = next;
        }
        return out;
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(0, RoundingMode.HALF_UP);
    }

    /** 러너가 매일 전략에 보여준 보유 여부를 기록한다. */
    private static List<Boolean> runnerStates(Supplier<BacktestStrategy> rule, List<Candle> candles) {
        BacktestStrategy inner = rule.get();
        List<Boolean> states = new ArrayList<>();
        BacktestStrategy recorder = (today, state) -> {
            states.add(state.hasPosition());
            return inner.onCandle(today, state);
        };
        new BacktestRunner(CostModel.defaults()).run(candles, recorder, new BigDecimal("1000000000000"));
        return states;
    }

    @Test
    void 그림자_보유는_러너가_전략에_보여준_보유와_매일_같다() {
        for (long seed = 1; seed <= 6; seed++) {
            List<Candle> candles = walk(seed, 600);
            for (Supplier<BacktestStrategy> rule : RULES) {
                List<Boolean> expected = runnerStates(rule, candles);
                BacktestStrategy strategy = rule.get();
                TaRuleBook.Shadow shadow = new TaRuleBook.Shadow();
                for (int i = 0; i < candles.size(); i++) {
                    assertEquals(expected.get(i), shadow.state().hasPosition(),
                            "seed=" + seed + " " + strategy.getClass().getSimpleName() + " day=" + i);
                    shadow.apply(strategy.onCandle(candles.get(i), shadow.state()), candles.get(i));
                }
            }
        }
    }

    @Test
    void 오늘_계획의_그림자_보유는_러너의_그날_보유와_같다() {
        TaRuleBook book = new TaRuleBook();
        List<Candle> candles = walk(42, 520);
        List<String> ids = book.ruleIds();
        assertEquals(List.of("TA-H1", "TA-H2", "TA-H3", "TA-H4", "TA-H5"), ids);
        for (int r = 0; r < ids.size(); r++) {
            List<Boolean> expected = runnerStates(RULES.get(r), candles);
            for (int day = 480; day < candles.size(); day++) {
                DailyRuleBook.RulePlan plan = book.plan(ids.get(r), "005930", candles.subList(0, day),
                        candles.get(day).date(), candles.get(day).open());
                assertEquals(expected.get(day), plan.shadowHolding(), ids.get(r) + " day=" + day);
                if (!plan.shadowHolding()) {
                    assertNull(plan.shadowAvgPrice());
                    assertTrue(plan.orders().stream().noneMatch(o -> o.kind() == DailyRuleBook.RuleOrder.Kind.SELL_AT_OPEN),
                            "미보유면 시가 매도 의사가 없다");
                }
            }
        }
    }

    @Test
    void 매수_스탑이_시가_이하면_시가_매수로_바꾼다() {
        // 일목: 어제 종가 > 구름 상단 → buyAtOpen(오늘 시가) — 꾸준히 오르는 봉
        List<Candle> up = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            BigDecimal c = BigDecimal.valueOf(10_000 + i * 50L);
            up.add(new Candle("005930", LocalDate.of(2026, 1, 1).plusDays(i), c, c.add(BigDecimal.TEN),
                    c.subtract(BigDecimal.TEN), c, 1L));
        }
        // 꾸준한 상승이라 규칙이 처음 판단할 수 있는 날(봉 78개 뒤) 시가 매수가 나온다 — 그 뒤로는 그림자가 이미 보유
        TaRuleBook book = new TaRuleBook();
        boolean sawBuyAtOpen = false;
        for (int day = 60; day < up.size(); day++) {
            DailyRuleBook.RulePlan plan = book.plan("TA-H1", "005930", up.subList(0, day), up.get(day).date(),
                    up.get(day).open());
            if (plan.has(DailyRuleBook.RuleOrder.Kind.BUY_AT_OPEN)) {
                sawBuyAtOpen = true;
                assertNull(plan.first(DailyRuleBook.RuleOrder.Kind.BUY_AT_OPEN).price());
                break;
            }
        }
        assertTrue(sawBuyAtOpen);
    }
}

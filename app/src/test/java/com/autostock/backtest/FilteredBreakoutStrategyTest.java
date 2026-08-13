package com.autostock.backtest;

import com.autostock.common.event.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FilteredBreakoutStrategy 검증 — 추세 필터(SMA20)와 전일 캔들 필터(양봉)가 각각
 * 진입을 막는/허용하는 케이스, 그리고 SMA 계산에 당일 데이터가 섞여 들어가지 않는지를
 * 합성 캔들로 확인한다.
 *
 * <p>SMA20은 "직전 20봉"이 쌓여야 계산되므로, 대부분의 테스트는 먼저 평평한(가격 변화
 * 없는) 20개짜리 워밍업 캔들을 흘려보내(이 구간에서는 필터가 데이터 부족으로 항상 진입을
 * 막으므로 아무 검증도 하지 않는다) SMA20 = 10000이 되도록 만든 뒤, 21번째 캔들에서
 * 필터 판정을 확인하는 방식을 쓴다.
 */
class FilteredBreakoutStrategyTest {

    private static final BigDecimal BASE_PRICE = new BigDecimal("10000");

    private Candle candle(int dayOffset, String open, String high, String low, String close) {
        return new Candle("TEST", LocalDate.of(2026, 1, 1).plusDays(dayOffset),
                new BigDecimal(open), new BigDecimal(high), new BigDecimal(low), new BigDecimal(close), 1000L);
    }

    private BacktestStrategy.PortfolioState notHolding() {
        return new BacktestStrategy.PortfolioState(0, BigDecimal.ZERO, new BigDecimal("1000000"));
    }

    /**
     * 평평한(시가=고가=저가=종가=10000) 캔들 20개를 만든다 — SMA20 = 10000이 되도록 하는
     * 워밍업 구간. 필요하면 마지막(19번째, index 19) 캔들만 별도 값으로 덮어써
     * 전일 캔들 필터 테스트에 쓸 수 있다.
     */
    private List<Candle> flatWarmup20(Candle lastCandleOverride) {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 19; i++) {
            candles.add(candle(i, "10000", "10050", "9950", "10000"));
        }
        candles.add(lastCandleOverride != null ? lastCandleOverride : candle(19, "10000", "10050", "9950", "10000"));
        return candles;
    }

    private void feed(FilteredBreakoutStrategy strategy, List<Candle> candles) {
        for (Candle c : candles) {
            strategy.onCandle(c, notHolding());
        }
    }

    // ── 추세 필터 ────────────────────────────────────────────────────────────

    @Test
    void 오늘_시가가_SMA20보다_낮으면_추세_필터가_진입을_막는다() {
        FilteredBreakoutStrategy strategy = new FilteredBreakoutStrategy(0.5, null, true, false);
        feed(strategy, flatWarmup20(null)); // SMA20 = 10000

        Candle today = candle(20, "9900", "10100", "9800", "10000"); // 오늘 시가(9900) < SMA20(10000)
        List<TradeIntent> intents = strategy.onCandle(today, notHolding());

        assertTrue(intents.isEmpty(), "시가가 SMA20보다 낮으면 추세 필터가 진입을 막아야 함");
    }

    @Test
    void 오늘_시가가_SMA20보다_높으면_추세_필터를_통과해_진입한다() {
        FilteredBreakoutStrategy strategy = new FilteredBreakoutStrategy(0.5, null, true, false);
        feed(strategy, flatWarmup20(null)); // SMA20 = 10000

        Candle today = candle(20, "10100", "10300", "10000", "10200"); // 오늘 시가(10100) > SMA20(10000)
        List<TradeIntent> intents = strategy.onCandle(today, notHolding());

        assertEquals(1, intents.size(), "추세 필터를 통과하면 buyStop 인텐트가 나와야 함");
        assertEquals(TradeIntent.Kind.BUY_STOP, intents.get(0).kind());
    }

    @Test
    void SMA20이_아직_쌓이지_않은_초반_구간에서는_추세_필터가_보수적으로_진입을_막는다() {
        FilteredBreakoutStrategy strategy = new FilteredBreakoutStrategy(0.5, null, true, false);
        Candle day0 = candle(0, "10000", "10100", "9900", "10050");
        strategy.onCandle(day0, notHolding());

        Candle day1 = candle(1, "10200", "10300", "10100", "10250"); // 시가가 아무리 높아도
        List<TradeIntent> intents = strategy.onCandle(day1, notHolding());

        assertTrue(intents.isEmpty(), "SMA20 계산에 필요한 20개 종가가 아직 쌓이지 않았으면 진입하면 안 됨");
    }

    // ── 전일 캔들 필터 ──────────────────────────────────────────────────────────

    @Test
    void 전일이_음봉이면_전일_캔들_필터가_진입을_막는다() {
        FilteredBreakoutStrategy strategy = new FilteredBreakoutStrategy(0.5, null, false, true);
        Candle bearishPrevCandle = candle(19, "10100", "10150", "9850", "9900"); // 종가(9900) < 시가(10100), 음봉
        feed(strategy, flatWarmup20(bearishPrevCandle));

        Candle today = candle(20, "10000", "10200", "9900", "10100");
        List<TradeIntent> intents = strategy.onCandle(today, notHolding());

        assertTrue(intents.isEmpty(), "전일이 음봉이면 전일 캔들 필터가 진입을 막아야 함");
    }

    @Test
    void 전일이_양봉이면_전일_캔들_필터를_통과해_진입한다() {
        FilteredBreakoutStrategy strategy = new FilteredBreakoutStrategy(0.5, null, false, true);
        Candle bullishPrevCandle = candle(19, "9900", "10150", "9850", "10100"); // 종가(10100) > 시가(9900), 양봉
        feed(strategy, flatWarmup20(bullishPrevCandle));

        Candle today = candle(20, "10000", "10200", "9900", "10100");
        List<TradeIntent> intents = strategy.onCandle(today, notHolding());

        assertEquals(1, intents.size(), "전일 캔들 필터를 통과하면 buyStop 인텐트가 나와야 함");
        assertEquals(TradeIntent.Kind.BUY_STOP, intents.get(0).kind());
    }

    // ── 두 필터 동시 적용 ───────────────────────────────────────────────────────

    @Test
    void 두_필터_모두_켜면_하나만_막혀도_진입하지_않는다() {
        // 추세는 통과(시가 > SMA20)하지만 전일이 음봉이라 필터에 막히는 케이스
        FilteredBreakoutStrategy strategy = new FilteredBreakoutStrategy(0.5, -0.03, true, true);
        Candle bearishPrevCandle = candle(19, "10100", "10150", "9850", "9900");
        feed(strategy, flatWarmup20(bearishPrevCandle));

        Candle today = candle(20, "10100", "10300", "10000", "10200"); // 시가(10100) > SMA20(10000)
        List<TradeIntent> intents = strategy.onCandle(today, notHolding());

        assertTrue(intents.isEmpty(), "전일 캔들 필터가 막으면 추세 필터를 통과해도 진입하면 안 됨");
    }

    @Test
    void 두_필터_모두_통과하면_손절가까지_포함해_진입한다() {
        FilteredBreakoutStrategy strategy = new FilteredBreakoutStrategy(0.5, -0.03, true, true);
        Candle bullishPrevCandle = candle(19, "9900", "10350", "9850", "10100"); // 양봉, 변동폭(high-low)=500
        feed(strategy, flatWarmup20(bullishPrevCandle));

        Candle today = candle(20, "10100", "10400", "10000", "10300"); // 시가(10100) > SMA20(10000)
        List<TradeIntent> intents = strategy.onCandle(today, notHolding());

        // target = 10100(오늘 시가) + 0.5 * (10350-9850) = 10100 + 250 = 10350
        BigDecimal expectedTarget = new BigDecimal("10350.0");
        assertEquals(2, intents.size());
        assertEquals(TradeIntent.Kind.BUY_STOP, intents.get(0).kind());
        assertEquals(0, expectedTarget.compareTo(intents.get(0).price()));
        assertEquals(TradeIntent.Kind.SELL_STOP, intents.get(1).kind());
    }

    // ── 보유 중 청산 로직은 원본과 동일 ─────────────────────────────────────────────

    @Test
    void 보유_중이면_필터와_무관하게_익일_시가_매도만_반환한다() {
        FilteredBreakoutStrategy strategy = new FilteredBreakoutStrategy(0.5, -0.03, true, true);
        BacktestStrategy.PortfolioState holding =
                new BacktestStrategy.PortfolioState(10, new BigDecimal("10000"), new BigDecimal("100000"));

        Candle today = candle(0, "9900", "9950", "9800", "9850"); // 음봉, 시가도 낮음 — 필터와 무관해야 함
        List<TradeIntent> intents = strategy.onCandle(today, holding);

        assertEquals(1, intents.size());
        assertEquals(TradeIntent.Kind.SELL_NEXT_OPEN, intents.get(0).kind());
    }

    // ── 룩어헤드 방지: SMA 계산에 당일(오늘) 데이터가 쓰이지 않는다 ───────────────────────

    @Test
    void SMA_계산은_오늘_고가_저가_종가와_무관하다_룩어헤드_없음_검증() {
        FilteredBreakoutStrategy strategyA = new FilteredBreakoutStrategy(0.5, -0.03, true, false);
        FilteredBreakoutStrategy strategyB = new FilteredBreakoutStrategy(0.5, -0.03, true, false);
        Candle sharedPrevCandle = candle(19, "9900", "10150", "9850", "10050");
        feed(strategyA, flatWarmup20(sharedPrevCandle));
        feed(strategyB, flatWarmup20(sharedPrevCandle));

        // 오늘 시가는 두 시나리오가 동일(10100 > SMA20=10000, 필터 통과)하지만
        // 고가/저가/종가는 극단적으로 다르게 준다 — SMA/필터 판정이 오늘 시가에만 반응해야 한다.
        Candle todayModestRange = candle(20, "10100", "10200", "10050", "10150");
        Candle todayExtremeRange = candle(20, "10100", "99999", "1", "50000");

        List<TradeIntent> intentsA = strategyA.onCandle(todayModestRange, notHolding());
        List<TradeIntent> intentsB = strategyB.onCandle(todayExtremeRange, notHolding());

        assertEquals(intentsA.size(), intentsB.size(),
                "오늘 고가/저가/종가가 달라도(시가는 동일) 진입 판정 자체는 같아야 함(룩어헤드 없음)");
        assertEquals(intentsA.get(0).kind(), intentsB.get(0).kind());
        assertEquals(0, intentsA.get(0).price().compareTo(intentsB.get(0).price()),
                "목표가는 오늘 시가와 전일 고가/저가로만 계산되므로 오늘 자신의 고가/저가/종가와 무관하게 같아야 함");
    }
}

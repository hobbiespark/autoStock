package com.autostock.backtest;

import com.autostock.common.event.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TimeSeriesMomentumStrategy 검증 — 21봉 판단 주기, 상승 추세면 진입/유지, 하락 추세면
 * 청산/미보유 유지가 규약대로 동작하는지 확인한다.
 *
 * <p>lookback(N)=5로 작게 잡아 테스트 데이터 크기를 줄인다. 판단 주기(21봉)는 클래스
 * 내부 상수라 항상 고정이므로, "22번째 캔들(index 20, dayIndex=21)에서 첫 판단이
 * 일어난다"는 사실을 이용해 웜업 구간을 구성한다.
 */
class TimeSeriesMomentumStrategyTest {

    private static final int LOOKBACK = 5;

    private Candle candle(int dayOffset, String open, String close) {
        BigDecimal o = new BigDecimal(open);
        BigDecimal c = new BigDecimal(close);
        BigDecimal high = o.max(c).add(new BigDecimal("50"));
        BigDecimal low = o.min(c).subtract(new BigDecimal("50"));
        return new Candle("TEST", LocalDate.of(2026, 1, 1).plusDays(dayOffset), o, high, low, c, 1000L);
    }

    private BacktestStrategy.PortfolioState notHolding() {
        return new BacktestStrategy.PortfolioState(0, BigDecimal.ZERO, new BigDecimal("1000000"));
    }

    private BacktestStrategy.PortfolioState holding() {
        return new BacktestStrategy.PortfolioState(10, new BigDecimal("10000"), new BigDecimal("100000"));
    }

    /**
     * dayIndex 0~19(20개 캔들)를 흘려보내는 웜업 — 이 구간은 판단일(21봉 주기)이 아니므로
     * 전략은 아무 인텐트도 내면 안 된다. closeAt15는 나중에 "N봉 전 종가"로 쓰일 값,
     * 그 외는 임의의 값(10000 고정)으로 채운다.
     */
    private void warmUp(TimeSeriesMomentumStrategy strategy, BacktestStrategy.PortfolioState state,
                         BigDecimal closeAt15) {
        for (int i = 0; i < 20; i++) {
            BigDecimal close = (i == 15) ? closeAt15 : new BigDecimal("10000");
            Candle c = candle(i, close.toPlainString(), close.toPlainString());
            List<TradeIntent> intents = strategy.onCandle(c, state);
            assertTrue(intents.isEmpty(), "판단일(21봉 주기)이 아닌 " + i + "번째 캔들에서는 인텐트가 없어야 함");
        }
    }

    @Test
    void 판단_주기가_아닌_날에는_보유_상태와_무관하게_아무_인텐트도_내지_않는다() {
        // warmUp 내부에서 이미 매 캔들마다 인텐트가 비어있는지 assert한다 — notHolding/holding 두 상태 모두 확인.
        warmUp(new TimeSeriesMomentumStrategy(LOOKBACK), notHolding(), new BigDecimal("10000"));
        warmUp(new TimeSeriesMomentumStrategy(LOOKBACK), holding(), new BigDecimal("10000"));
    }

    @Test
    void 판단일에_어제_종가가_N봉_전보다_높으면_미보유_상태에서_매수한다() {
        TimeSeriesMomentumStrategy strategy = new TimeSeriesMomentumStrategy(LOOKBACK);
        // index15 종가(N봉 전 종가) = 10000, index19(=어제) 종가는 warmUp에서 10000로 고정되므로
        // 상승 추세를 만들려면 index19 이후 어제 종가 위치를 다시 확인해야 한다.
        // dayIndex=21(index21) 판단 시 "어제 종가" = index20 종가, "N봉 전 종가" = index15 종가.
        warmUp(strategy, notHolding(), new BigDecimal("10000")); // index0~19, index15 종가=10000

        // index20 — 아직 판단일이 아님(dayIndex=20). 종가를 N봉전(index15=10000)보다 높게 만든다.
        Candle day20 = candle(20, "10500", "10500");
        List<TradeIntent> notJudgmentIntents = strategy.onCandle(day20, notHolding());
        assertTrue(notJudgmentIntents.isEmpty(), "dayIndex=20은 아직 판단일이 아니어야 함");

        // index21 — dayIndex=21, 21의 배수라 판단일. 어제(index20) 종가(10500) > N봉전(index15) 종가(10000) → 상승추세.
        Candle day21 = candle(21, "10600", "10650");
        List<TradeIntent> intents = strategy.onCandle(day21, notHolding());

        assertEquals(1, intents.size(), "상승 추세 + 미보유면 매수 인텐트 1건이어야 함");
        assertEquals(TradeIntent.Kind.BUY_STOP, intents.get(0).kind(), "buyAtOpen은 BUY_STOP 종류로 표현됨");
        assertEquals(0, new BigDecimal("10600").compareTo(intents.get(0).price()),
                "buyAtOpen 가격은 오늘 시가와 같아야 함");
    }

    @Test
    void 판단일에_어제_종가가_N봉_전보다_낮으면_보유_상태에서_청산한다() {
        TimeSeriesMomentumStrategy strategy = new TimeSeriesMomentumStrategy(LOOKBACK);
        warmUp(strategy, holding(), new BigDecimal("10000")); // index0~19, index15 종가=10000, 이미 보유 중이라고 가정

        // index20 — 아직 판단일 아님. 종가를 N봉전(10000)보다 낮게 만든다.
        Candle day20 = candle(20, "9500", "9500");
        strategy.onCandle(day20, holding());

        // index21 — 판단일. 어제(index20) 종가(9500) < N봉전(index15) 종가(10000) → 하락추세, 보유 중이므로 청산.
        Candle day21 = candle(21, "9400", "9350");
        List<TradeIntent> intents = strategy.onCandle(day21, holding());

        assertEquals(1, intents.size(), "하락 추세 + 보유면 청산 인텐트 1건이어야 함");
        assertEquals(TradeIntent.Kind.SELL_NEXT_OPEN, intents.get(0).kind());
    }

    @Test
    void 판단일에_하락_추세인데_미보유면_아무_인텐트도_내지_않는다() {
        TimeSeriesMomentumStrategy strategy = new TimeSeriesMomentumStrategy(LOOKBACK);
        warmUp(strategy, notHolding(), new BigDecimal("10000"));

        Candle day20 = candle(20, "9500", "9500");
        strategy.onCandle(day20, notHolding());

        Candle day21 = candle(21, "9400", "9350"); // 하락 추세, 이미 미보유
        List<TradeIntent> intents = strategy.onCandle(day21, notHolding());

        assertTrue(intents.isEmpty(), "하락 추세라도 이미 미보유면 청산할 것이 없어 인텐트가 없어야 함");
    }

    @Test
    void 판단일에_상승_추세인데_이미_보유면_추가_매수하지_않는다() {
        TimeSeriesMomentumStrategy strategy = new TimeSeriesMomentumStrategy(LOOKBACK);
        warmUp(strategy, holding(), new BigDecimal("10000"));

        Candle day20 = candle(20, "10500", "10500");
        strategy.onCandle(day20, holding());

        Candle day21 = candle(21, "10600", "10650"); // 상승 추세, 이미 보유 중
        List<TradeIntent> intents = strategy.onCandle(day21, holding());

        assertTrue(intents.isEmpty(), "상승 추세라도 이미 보유 중이면 추가 매수(재진입)하면 안 됨");
    }
}

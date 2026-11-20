package com.autostock.strategy;

import com.autostock.common.event.MarketTick;
import com.autostock.common.event.Side;
import com.autostock.common.event.SignalDecision;
import com.autostock.common.util.Price;
import com.autostock.common.util.StockCode;
import com.autostock.market.MarketDataPort;
import com.autostock.risk.MultiStrategyRiskProperties;
import com.autostock.risk.OneShareCap;
import com.autostock.risk.TradingCapitalProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.autostock.strategy.StrategyTestSupport.CLOCK;
import static com.autostock.strategy.StrategyTestSupport.TODAY;
import static com.autostock.strategy.StrategyTestSupport.candle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 전략이 1주 상한을 넘는 종목을 신호 전에 거르는지(VALUNIVO 917, 데브 11-20 "예산에 맞춰 거르기"). */
class StrategyOneShareCapTest {

    private static final TradingCapitalProperties CAPITAL = new TradingCapitalProperties(2_000_000, 2.0, 500, 0.01);
    private static final BigDecimal EQUITY = new BigDecimal("484138536");

    /** VB-DAY 슬리브를 바꿔 상한을 정한다: 0.15/3 → 20만, 0.04/5 → 10만(하한). */
    private static OneShareCap capWith(String strategy, double fraction, int maxPositions) {
        return new OneShareCap(new MultiStrategyRiskProperties(true, List.of(
                new MultiStrategyRiskProperties.Sleeve(strategy, fraction, maxPositions))), CAPITAL, () -> EQUITY);
    }

    private static DayBreakoutStrategy dayBreakout(StrategyTestSupport t, OneShareCap cap) {
        LiveMarket market = mock(LiveMarket.class);
        StrategyGuard guard = mock(StrategyGuard.class);
        when(guard.active(anyBoolean())).thenReturn(true);
        when(guard.today()).thenReturn(TODAY);
        when(guard.now()).thenReturn(LocalTime.of(9, 30));
        // 005930: 전일 양봉(99,000→102,000, 고 104,000 저 99,000), 시가 101,000 → 목표가 103,500
        when(market.completedBars("005930")).thenReturn(StrategyTestSupport.flatThen("005930", 19, "100000",
                candle("005930", TODAY.minusDays(1), "99000", "104000", "99000", "102000")));
        when(market.knownOpen("005930")).thenReturn(new BigDecimal("101000"));
        DayBreakoutStrategy s = new DayBreakoutStrategy(new DayBreakoutProperties(true, List.of("005930"), 0.5, 20,
                0.018, 0.015, LocalTime.of(14, 30), LocalTime.of(15, 15), 3, 6, 0.005, 0.01),
                market, t.book, t.outbox, guard, t.events::add, CLOCK, null, cap);
        s.prepareToday();
        return s;
    }

    private static MarketTick tick(String price) {
        return new MarketTick(new StockCode("005930"), new Price(new BigDecimal(price)), 1, CLOCK.instant(),
                MarketTick.Source.LIVE);
    }

    @Test
    void 단타_시가가_상한을_넘으면_돌파해도_사지_않고_보류를_한_번만_남긴다() {
        StrategyTestSupport t = new StrategyTestSupport();
        // 상한 10만 < 시가 101,000 × 1.005 = 101,505
        DayBreakoutStrategy s = dayBreakout(t, capWith("VB-DAY", 0.04, 5));

        s.onTick(tick("103500"));
        s.onTick(tick("104000"));

        assertTrue(t.signals().isEmpty(), "관문이 어차피 거부할 종목 — 신호를 내지 않는다");
        List<SignalDecision> skips = t.decisions().stream()
                .filter(d -> d.conclusion().equals("SKIP")).toList();
        assertEquals(1, skips.size(), "하루 한 번");
        assertTrue(skips.get(0).reason().contains("1주 값이 종목 상한 초과"));
    }

    @Test
    void 단타_상한_안이면_예전처럼_산다() {
        StrategyTestSupport t = new StrategyTestSupport();
        DayBreakoutStrategy s = dayBreakout(t, capWith("VB-DAY", 0.15, 3));   // 상한 20만

        s.onTick(tick("103500"));

        assertEquals(1, t.signals().size());
        assertEquals(Side.BUY, t.signals().get(0).side());
    }

    @Test
    void 종가_후보는_상한을_넘는_종목을_건너뛰고_다음_후보가_자리를_채운다() {
        OvernightCloseProperties props = new OvernightCloseProperties(true, 2, 5, 20, 0.97, 0.05, 0.02, 20,
                List.of("16"));
        List<MarketDataPort.RankedStock> ranked = List.of(
                new MarketDataPort.RankedStock("111111", "비쌈", new BigDecimal("215000"), new BigDecimal("8"), 9),
                new MarketDataPort.RankedStock("222222", "a", new BigDecimal("10000"), new BigDecimal("8"), 8),
                new MarketDataPort.RankedStock("333333", "b", new BigDecimal("10000"), new BigDecimal("8"), 7));
        MarketDataPort.StockQuote good = new MarketDataPort.StockQuote("x", new BigDecimal("10000"),
                new BigDecimal("9500"), new BigDecimal("10200"), new BigDecimal("9500"));
        Map<String, MarketDataPort.StockQuote> quotes = Map.of(
                "111111", new MarketDataPort.StockQuote("x", new BigDecimal("215000"), new BigDecimal("200000"),
                        new BigDecimal("216000"), new BigDecimal("200000")),
                "222222", good, "333333", good);
        OneShareCap cap = capWith("ON-CLOSE", 0.15, 3);   // 상한 20만 < 215,000 × 1.05
        int[] quoteChecks = {0};

        List<OvernightCloseStrategy.Pick> picks = OvernightCloseStrategy.choose(ranked, Set.of(), props, code -> {
            quoteChecks[0]++;
            return quotes.get(code);
        }, limit -> cap.exceeds("ON-CLOSE", limit));

        assertEquals(List.of("222222", "333333"), picks.stream().map(OvernightCloseStrategy.Pick::code).toList(),
                "비싼 1순위 대신 다음 두 후보");
        assertEquals(2, quoteChecks[0], "건너뛴 종목은 현재가 확인(TR)도 쓰지 않는다");
    }
}

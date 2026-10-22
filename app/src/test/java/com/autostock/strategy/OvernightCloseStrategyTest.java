package com.autostock.strategy;

import com.autostock.common.event.Side;
import com.autostock.common.event.Signal;
import com.autostock.market.MarketDataPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.autostock.strategy.StrategyTestSupport.CLOCK;
import static com.autostock.strategy.StrategyTestSupport.TODAY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 종가 → 다음 날 시가(ON-CLOSE) — 15:15 후보, 15:26 종가 매수, 다음 날 09:00:30 매도. */
class OvernightCloseStrategyTest {

    private static final OvernightCloseProperties PROPS = new OvernightCloseProperties(true, 3, 5, 20, 0.97, 0.05,
            0.02, 20, List.of("16", "4", "5", "11"));

    private StrategyTestSupport t;
    private LiveMarket market;
    private StrategyGuard guard;
    private OvernightCloseStrategy strategy;

    private static MarketDataPort.RankedStock rank(String code, String name, String price, String rate, long volume) {
        return new MarketDataPort.RankedStock(code, name, new BigDecimal(price), new BigDecimal(rate), volume);
    }

    private static MarketDataPort.StockQuote quote(String current, String open, String high) {
        return new MarketDataPort.StockQuote("x", new BigDecimal(current), new BigDecimal(open), new BigDecimal(high),
                new BigDecimal(open));
    }

    @BeforeEach
    void setUp() {
        t = new StrategyTestSupport();
        market = mock(LiveMarket.class);
        guard = mock(StrategyGuard.class);
        when(guard.active(anyBoolean())).thenReturn(true);
        when(guard.today()).thenReturn(TODAY);
        strategy = new OvernightCloseStrategy(PROPS, market, t.book, t.outbox, guard, t.events::add, CLOCK);
    }

    @Test
    void 후보는_교집합_스팩_제외_등락률_범위_거래대금_순() {
        List<MarketDataPort.RankedStock> all = List.of(
                rank("111111", "큰거래", "10000", "+8.00", 5_000_000),      // 거래대금 500억
                rank("222222", "작은거래", "10000", "+12.00", 1_000_000),   // 100억
                rank("333333", "과열", "10000", "+25.00", 9_000_000),       // 범위 밖
                rank("444444", "덜오름", "10000", "+3.00", 9_000_000),      // 범위 밖
                rank("555555", "가나스팩1호", "10000", "+9.00", 9_000_000),  // 스팩
                rank("666666", "ETF같은", "10000", "+9.00", 9_000_000));    // 다른 목록에 없음
        List<MarketDataPort.RankedStock> other = all.subList(0, 5);

        List<MarketDataPort.RankedStock> c = OvernightCloseStrategy.candidates(List.of(all, other), PROPS);

        assertEquals(List.of("111111", "222222"), c.stream().map(MarketDataPort.RankedStock::code).toList());
    }

    @Test
    void 고가_근처_양봉인_처음_3종목을_고른다() {
        List<MarketDataPort.RankedStock> ranked = List.of(
                rank("111111", "a", "10000", "+8", 9), rank("222222", "b", "10000", "+8", 8),
                rank("333333", "c", "10000", "+8", 7), rank("444444", "d", "10000", "+8", 6),
                rank("555555", "e", "10000", "+8", 5));
        Map<String, MarketDataPort.StockQuote> quotes = Map.of(
                "111111", quote("10000", "9500", "10200"),   // 고가의 98% — 통과
                "222222", quote("10000", "9500", "10500"),   // 고가의 95% — 윗꼬리
                "333333", quote("10000", "10100", "10000"),  // 음봉
                "444444", quote("10000", "9000", "10000"),
                "555555", quote("10000", "9000", "10000"));

        List<OvernightCloseStrategy.Pick> picks = OvernightCloseStrategy.choose(ranked, Set.of("444444"), PROPS, quotes::get);

        assertEquals(List.of("111111", "555555"), picks.stream().map(OvernightCloseStrategy.Pick::code).toList(),
                "윗꼬리·음봉 제외, 아직 들고 있는 종목 제외");
    }

    @Test
    void 스캔_뒤_15시26분에_15시15분_가격의_105퍼센트로_산다() {
        List<MarketDataPort.RankedStock> rows = List.of(rank("111111", "a", "10000", "+8", 9_000_000));
        when(market.risingRanking(anyString())).thenReturn(rows);
        when(market.quote("111111")).thenReturn(quote("10000", "9500", "10100"));

        strategy.scan();
        strategy.buyAtClose();

        List<Signal> signals = t.signals();
        assertEquals(1, signals.size());
        assertEquals("ON-CLOSE", signals.get(0).strategyId());
        assertEquals(0, new BigDecimal("10500").compareTo(signals.get(0).refPrice().value()));
        assertTrue(t.runs().get(0).summary().contains("선택 1"));
    }

    @Test
    void 순위_조회가_비면_오늘은_사지_않는다() {
        when(market.risingRanking(anyString())).thenReturn(List.of());
        strategy.scan();
        strategy.buyAtClose();
        assertTrue(t.signals().isEmpty());
        assertEquals(1, t.runs().get(0).errors());
    }

    @Test
    void 다음_날_아침에_보유를_매수호가의_98퍼센트로_판다() {
        t.filled("ON-CLOSE", "111111", Side.BUY, 100, "10000");
        when(market.bestBid("111111")).thenReturn(new BigDecimal("10800"));

        strategy.morningExit();

        Signal sell = t.signals().get(0);
        assertEquals(Side.SELL, sell.side());
        assertEquals(0, new BigDecimal("10584").compareTo(sell.refPrice().value()));
        assertTrue(t.runs().get(0).summary().contains("아침 매도 1종목"));
    }
}

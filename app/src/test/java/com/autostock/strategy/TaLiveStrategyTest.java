package com.autostock.strategy;

import com.autostock.common.event.Candle;
import com.autostock.common.event.MarketTick;
import com.autostock.common.event.Side;
import com.autostock.common.event.Signal;
import com.autostock.common.util.Price;
import com.autostock.common.util.StockCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.autostock.strategy.StrategyTestSupport.CLOCK;
import static com.autostock.strategy.StrategyTestSupport.TODAY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 차트 기법 라이브 — 그림자 포지션과 실제 보유를 맞추는 규칙, 장중 돌파·손절 감시. */
class TaLiveStrategyTest {

    private static final DailyRuleBook.RuleOrder BUY_OPEN = new DailyRuleBook.RuleOrder(DailyRuleBook.RuleOrder.Kind.BUY_AT_OPEN, null);
    private static final DailyRuleBook.RuleOrder SELL_OPEN = new DailyRuleBook.RuleOrder(DailyRuleBook.RuleOrder.Kind.SELL_AT_OPEN, null);

    private StrategyTestSupport t;
    private LiveMarket market;
    private StrategyGuard guard;
    private final Map<String, DailyRuleBook.RulePlan> plans = new HashMap<>();
    private TaLiveStrategy strategy;

    private static DailyRuleBook.RuleOrder order(DailyRuleBook.RuleOrder.Kind kind, String price) {
        return new DailyRuleBook.RuleOrder(kind, new BigDecimal(price));
    }

    /** 규칙 두 개(TA-H1·TA-H2)만 있는 가짜 묶음 — plans에 넣은 계획을 돌려준다. */
    private final DailyRuleBook fake = new DailyRuleBook() {
        @Override
        public List<String> ruleIds() {
            return List.of("TA-H1", "TA-H2");
        }

        @Override
        public String ruleName(String ruleId) {
            return ruleId;
        }

        @Override
        public RulePlan plan(String ruleId, String symbol, List<Candle> completed, LocalDate today, BigDecimal todayOpen) {
            return plans.getOrDefault(ruleId, new RulePlan(false, null, List.of()));
        }
    };

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        t = new StrategyTestSupport();
        market = mock(LiveMarket.class);
        guard = mock(StrategyGuard.class);
        when(guard.active(anyBoolean())).thenReturn(true);
        when(guard.today()).thenReturn(TODAY);
        when(guard.now()).thenReturn(LocalTime.of(9, 30));
        List<Candle> bars = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            bars.add(StrategyTestSupport.candle("005930", TODAY.minusDays(300 - i), "100000", "100000", "100000", "100000"));
        }
        when(market.completedBars("005930")).thenReturn(bars);
        when(market.todayOpen("005930")).thenReturn(new BigDecimal("100500"));
        when(market.bestAsk(anyString())).thenReturn(new BigDecimal("100600"));
        when(market.bestBid(anyString())).thenReturn(new BigDecimal("100400"));
        ObjectProvider<DailyRuleBook> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(fake);
        strategy = new TaLiveStrategy(new TaLiveProperties(true, List.of("005930"), 250, 0.005, 0.01), provider, market,
                t.book, t.outbox, guard, t.events::add, CLOCK);
    }

    private static MarketTick tick(String price) {
        return new MarketTick(new StockCode("005930"), new Price(new BigDecimal(price)), 1, CLOCK.instant(),
                MarketTick.Source.LIVE);
    }

    @Test
    void 둘_다_미보유면_시가_매수_신호를_따른다() {
        plans.put("TA-H1", new DailyRuleBook.RulePlan(false, null, List.of(BUY_OPEN)));

        strategy.runIfDue("09:01");

        List<Signal> signals = t.signals();
        assertEquals(1, signals.size());
        assertEquals("TA-H1", signals.get(0).strategyId());
        assertEquals(Side.BUY, signals.get(0).side());
        assertEquals(0, new BigDecimal("100600").compareTo(signals.get(0).refPrice().value()), "최우선 매도호가");
        assertTrue(t.runs().get(0).summary().contains("시가 매수 1"));
    }

    @Test
    void 규칙상_보유인데_실제_미보유면_따라_사지_않는다() {
        plans.put("TA-H1", new DailyRuleBook.RulePlan(true, new BigDecimal("90000"), List.of()));
        strategy.runIfDue("09:01");
        assertTrue(t.signals().isEmpty());
        assertTrue(t.decisions().stream().anyMatch(d -> d.reason().contains("따라 사지 않음")));
    }

    @Test
    void 규칙상_미보유인데_실제_보유면_시가에_판다() {
        t.filled("TA-H2", "005930", Side.BUY, 4, "99000");
        strategy.runIfDue("09:01");
        Signal sell = t.signals().get(0);
        assertEquals("TA-H2", sell.strategyId());
        assertEquals(Side.SELL, sell.side());
        assertEquals(0, new BigDecimal("99396").compareTo(sell.refPrice().value()), "100,400 × 0.99");
    }

    @Test
    void 둘_다_보유면_청산_신호를_따른다() {
        t.filled("TA-H1", "005930", Side.BUY, 4, "99000");
        plans.put("TA-H1", new DailyRuleBook.RulePlan(true, new BigDecimal("99000"), List.of(SELL_OPEN)));
        strategy.runIfDue("09:01");
        assertEquals(Side.SELL, t.signals().get(0).side());
    }

    @Test
    void 장중_돌파_매수와_짝지은_손절을_감시한다() {
        plans.put("TA-H2", new DailyRuleBook.RulePlan(false, null, List.of(
                order(DailyRuleBook.RuleOrder.Kind.BUY_STOP, "102000"), order(DailyRuleBook.RuleOrder.Kind.SELL_STOP, "98940"))));
        strategy.runIfDue("09:01");
        assertTrue(t.signals().isEmpty(), "아직 돌파 전");

        strategy.onTick(tick("101900"));
        assertTrue(t.signals().isEmpty());
        strategy.onTick(tick("102000"));
        assertEquals(1, t.signals().size());
        assertEquals(Side.BUY, t.signals().get(0).side());

        // 매수 체결 → 짝지은 손절이 감시로 옮겨진다
        t.filled("TA-H2", "005930", Side.BUY, 4, "102100");
        strategy.onTick(tick("99000"));
        assertEquals(1, t.signals().size(), "손절가(98,940) 위");
        strategy.onTick(tick("98900"));
        assertEquals(2, t.signals().size());
        assertEquals(Side.SELL, t.signals().get(1).side());
        strategy.onTick(tick("98000"));
        assertEquals(2, t.signals().size(), "손절은 한 번만");
    }

    @Test
    void 하루_한_번만_판단한다_데이터가_모자라면_오류로_센다() {
        strategy.runIfDue("09:01");
        strategy.runIfDue("장중 기동");
        assertEquals(1, t.runs().size());

        when(market.todayOpen("005930")).thenReturn(null);
        when(guard.today()).thenReturn(TODAY.plusDays(1));
        strategy.runIfDue("09:01");
        assertEquals(1, t.runs().get(1).errors());
    }
}

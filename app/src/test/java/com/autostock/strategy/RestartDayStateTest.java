package com.autostock.strategy;

import com.autostock.common.event.Candle;
import com.autostock.common.event.MarketTick;
import com.autostock.common.event.StrategyRunCompleted;
import com.autostock.common.util.Price;
import com.autostock.common.util.StockCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.autostock.strategy.StrategyTestSupport.CLOCK;
import static com.autostock.strategy.StrategyTestSupport.TODAY;
import static com.autostock.strategy.StrategyTestSupport.candle;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** VALUNIVO 925: 장중 재기동 뒤 오늘 이미 한 판단(TA)·진입(VB-DAY)을 다시 하지 않는다(11-06 카카오 사고팔기 반복). */
class RestartDayStateTest {

    private static StrategyGuard guard() {
        StrategyGuard guard = mock(StrategyGuard.class);
        when(guard.active(anyBoolean())).thenReturn(true);
        when(guard.today()).thenReturn(TODAY);
        when(guard.now()).thenReturn(LocalTime.of(9, 36));
        return guard;
    }

    @SuppressWarnings("unchecked")
    private static TaLiveStrategy ta(StrategyTestSupport t, StrategyStateStore store) {
        LiveMarket market = mock(LiveMarket.class);
        List<Candle> bars = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            bars.add(candle("035720", TODAY.minusDays(300 - i), "31000", "31000", "31000", "31000"));
        }
        when(market.completedBars("035720")).thenReturn(bars);
        when(market.todayOpen("035720")).thenReturn(new BigDecimal("31900"));
        when(market.bestAsk(anyString())).thenReturn(new BigDecimal("31900"));
        DailyRuleBook book = new DailyRuleBook() {
            @Override
            public List<String> ruleIds() {
                return List.of("TA-H3");
            }

            @Override
            public String ruleName(String ruleId) {
                return ruleId;
            }

            @Override
            public RulePlan plan(String ruleId, String symbol, List<Candle> completed, LocalDate today, BigDecimal open) {
                return new RulePlan(false, null, List.of(new RuleOrder(RuleOrder.Kind.BUY_AT_OPEN, null)));
            }
        };
        ObjectProvider<DailyRuleBook> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(book);
        return new TaLiveStrategy(new TaLiveProperties(true, List.of("035720"), 250, 0.005, 0.01), provider, market,
                t.book, t.outbox, guard(), t.events::add, CLOCK, null, store);
    }

    @Test
    void TA는_재기동_전에_오늘_판단했으면_다시_하지_않는다() {
        StrategyTestSupport t = new StrategyTestSupport();
        StrategyStateStore store = mock(StrategyStateStore.class);
        when(store.loadLastDecisionDates(TaLiveStrategy.RUN_STATE_ID)).thenReturn(Map.of("035720", TODAY));

        ta(t, store).runIfDue("장중 기동 09:36");

        assertThat(t.signals()).isEmpty();
        assertThat(t.events).noneMatch(e -> e instanceof StrategyRunCompleted);
    }

    @Test
    void TA는_오늘_판단이_없으면_예전처럼_판단하고_판단일을_남긴다() {
        StrategyTestSupport t = new StrategyTestSupport();
        StrategyStateStore store = mock(StrategyStateStore.class);
        when(store.loadLastDecisionDates(TaLiveStrategy.RUN_STATE_ID)).thenReturn(Map.of("035720", TODAY.minusDays(1)));

        ta(t, store).runIfDue("09:01");

        assertThat(t.signals()).hasSize(1);
        verify(store).saveLastDecisionDate(eq(TaLiveStrategy.RUN_STATE_ID), eq("035720"), eq(TODAY), any());
    }

    private static DayBreakoutStrategy vb(StrategyTestSupport t, StrategyStateStore store) {
        LiveMarket market = mock(LiveMarket.class);
        // 전일 양봉(99,000→102,000, 고 104,000 저 99,000), 시가 101,000 → 목표가 103,500
        when(market.completedBars("005930")).thenReturn(StrategyTestSupport.flatThen("005930", 19, "100000",
                candle("005930", TODAY.minusDays(1), "99000", "104000", "99000", "102000")));
        when(market.knownOpen("005930")).thenReturn(new BigDecimal("101000"));
        DayBreakoutStrategy s = new DayBreakoutStrategy(new DayBreakoutProperties(true, List.of("005930"), 0.5, 20,
                0.018, 0.015, LocalTime.of(14, 30), LocalTime.of(15, 15), 3, 6, 0.005, 0.01),
                market, t.book, t.outbox, guard(), t.events::add, CLOCK, null, null, store);
        s.prepareToday();
        return s;
    }

    private static MarketTick tick(String price) {
        return new MarketTick(new StockCode("005930"), new Price(new BigDecimal(price)), 1, CLOCK.instant(),
                MarketTick.Source.LIVE);
    }

    @Test
    void VB는_재기동_전에_오늘_진입한_종목에_다시_진입하지_않는다() {
        StrategyTestSupport t = new StrategyTestSupport();
        StrategyStateStore store = mock(StrategyStateStore.class);
        when(store.loadLastDecisionDates(DayBreakoutStrategy.ENTRY_STATE_ID)).thenReturn(Map.of("005930", TODAY));

        vb(t, store).onTick(tick("103500"));

        assertThat(t.signals()).isEmpty();
        verify(store, never()).saveLastDecisionDate(anyString(), anyString(), any(), any());
    }

    @Test
    void VB는_오늘_진입이_없으면_예전처럼_진입하고_진입일을_남긴다() {
        StrategyTestSupport t = new StrategyTestSupport();
        StrategyStateStore store = mock(StrategyStateStore.class);
        when(store.loadLastDecisionDates(DayBreakoutStrategy.ENTRY_STATE_ID)).thenReturn(Map.of());

        vb(t, store).onTick(tick("103500"));

        assertThat(t.signals()).hasSize(1);
        verify(store).saveLastDecisionDate(eq(DayBreakoutStrategy.ENTRY_STATE_ID), eq("005930"), eq(TODAY), any());
    }
}

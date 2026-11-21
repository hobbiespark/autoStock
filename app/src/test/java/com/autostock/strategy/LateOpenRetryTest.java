package com.autostock.strategy;

import com.autostock.common.event.Signal;
import com.autostock.common.event.StrategyRunCompleted;
import com.autostock.market.KiwoomDailyChartService;
import com.autostock.market.MarketCalendarService;
import com.autostock.market.MarketDataPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static com.autostock.strategy.StrategyTestSupport.CLOCK;
import static com.autostock.strategy.StrategyTestSupport.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** VALUNIVO 924: 지연 개장일(11-19 수능일 10:00 개장) — 시가 다시 받기, TA 판단 미루기. */
class LateOpenRetryTest {

    @Test
    @SuppressWarnings("unchecked")
    void 못_받은_시가만_다시_받아_캐시에_채운다() {
        MarketDataPort marketData = mock(MarketDataPort.class);
        MarketCalendarService calendar = mock(MarketCalendarService.class);
        when(calendar.isTradingDay(any())).thenReturn(true);
        LiveMarket market = new LiveMarket(mock(KiwoomDailyChartService.class), marketData, mock(ObjectProvider.class),
                calendar, Clock.fixed(Instant.parse("2026-11-19T01:00:05Z"), ZoneId.of("UTC")));
        market.register("VB-DAY", List.of("005930"));
        when(marketData.stockQuote("005930"))
                .thenReturn(MarketDataPort.StockQuote.EMPTY)   // 09:00:05 — 장 시작 전
                .thenReturn(new MarketDataPort.StockQuote("삼성전자", new BigDecimal("61000"), new BigDecimal("60500"), null, null));

        market.prefetchOpens();
        assertThat(market.knownOpen("005930")).isNull();

        market.retryMissingOpens();   // 10:00:05 — 개장 뒤
        assertThat(market.knownOpen("005930")).isEqualByComparingTo("60500");

        market.retryMissingOpens();   // 다 받았으면 더 조회하지 않는다(세 번째 응답을 정하지 않아도 그대로)
        assertThat(market.knownOpen("005930")).isEqualByComparingTo("60500");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 모든_종목_시가가_없으면_TA_판단을_미뤘다가_개장_뒤에_한다() {
        StrategyTestSupport t = new StrategyTestSupport();
        LiveMarket market = mock(LiveMarket.class);
        StrategyGuard guard = mock(StrategyGuard.class);
        when(guard.active(anyBoolean())).thenReturn(true);
        when(guard.today()).thenReturn(TODAY);
        when(guard.now()).thenReturn(LocalTime.of(9, 1));
        List<com.autostock.common.event.Candle> bars = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            bars.add(StrategyTestSupport.candle("005930", TODAY.minusDays(300 - i), "100000", "100000", "100000", "100000"));
        }
        when(market.completedBars("005930")).thenReturn(bars);
        when(market.bestAsk(anyString())).thenReturn(new BigDecimal("100600"));
        DailyRuleBook book = new DailyRuleBook() {
            @Override
            public List<String> ruleIds() {
                return List.of("TA-H1");
            }

            @Override
            public String ruleName(String ruleId) {
                return ruleId;
            }

            @Override
            public RulePlan plan(String ruleId, String symbol, List<com.autostock.common.event.Candle> completed,
                                 LocalDate today, BigDecimal todayOpen) {
                return new RulePlan(false, null, List.of(new RuleOrder(RuleOrder.Kind.BUY_AT_OPEN, null)));
            }
        };
        ObjectProvider<DailyRuleBook> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(book);
        TaLiveStrategy strategy = new TaLiveStrategy(new TaLiveProperties(true, List.of("005930"), 250, 0.005, 0.01),
                provider, market, t.book, t.outbox, guard, t.events::add, CLOCK);

        when(market.todayOpen("005930")).thenReturn(null);   // 09:01 장 시작 전
        strategy.runIfDue("09:01");
        assertThat(t.signals()).isEmpty();
        assertThat(t.events).noneMatch(e -> e instanceof StrategyRunCompleted);

        when(market.todayOpen("005930")).thenReturn(new BigDecimal("100500"));   // 개장 뒤 따라잡기
        strategy.runIfDue("장중 기동 10:01");
        List<Signal> signals = t.signals();
        assertThat(signals).hasSize(1);
        assertThat(t.events).anyMatch(e -> e instanceof StrategyRunCompleted);

        strategy.runIfDue("장중 기동 10:02");
        assertThat(t.signals()).hasSize(1);   // 판단은 하루 한 번
    }
}

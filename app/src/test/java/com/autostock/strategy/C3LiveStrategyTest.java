package com.autostock.strategy;

import com.autostock.common.event.Candle;
import com.autostock.common.event.Fill;
import com.autostock.common.event.Side;
import com.autostock.common.event.Signal;
import com.autostock.marketdata.KiwoomDailyChartService;
import com.autostock.marketdata.MarketCalendarService;
import com.autostock.marketdata.MarketHolidayRepository;
import com.autostock.risk.PositionBook;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * C3LiveStrategy 검증 — marketdata 서비스는 스텁으로 대체하고(실제 REST 호출 없음),
 * 국면 ON/OFF와 종목별 모멘텀에 따른 Signal 발행, 종목 단위 예외 격리, enabled=false
 * no-op을 확인한다.
 */
class C3LiveStrategyTest {

    private static final LocalDate D0 = LocalDate.of(2024, 1, 1);

    // repository는 mock — market_holidays에 해당 연도 데이터가 없으므로 MarketCalendarService는
    // TradingCalendar 하드코딩 폴백으로 판정한다(기존 동작과 동일, RiskGateTest와 같은 이유).
    private final MarketCalendarService marketCalendarService = new MarketCalendarService(mock(MarketHolidayRepository.class));

    /**
     * {@link KiwoomDailyChartService#fetchDaily}를 실제 REST 호출 없이 사전에 준비된
     * 캔들 목록으로 대체하는 스텁. marketQueryService는 절대 참조되지 않으므로 null로 넘긴다.
     */
    private static final class StubChartService extends KiwoomDailyChartService {
        private final Map<String, List<Candle>> bySymbol = new HashMap<>();
        private final Map<String, RuntimeException> failures = new HashMap<>();

        StubChartService() {
            super(null);
        }

        void put(String symbol, List<Candle> candles) {
            bySymbol.put(symbol, candles);
        }

        void failFor(String symbol, RuntimeException ex) {
            failures.put(symbol, ex);
        }

        @Override
        public List<Candle> fetchDaily(String symbol, LocalDate baseDate, int minCount) {
            if (failures.containsKey(symbol)) {
                throw failures.get(symbol);
            }
            return bySymbol.getOrDefault(symbol, List.of());
        }
    }

    private Candle candle(String symbol, int dayOffset, String close) {
        BigDecimal c = new BigDecimal(close);
        return new Candle(symbol, D0.plusDays(dayOffset), c, c, c, c, 1000L);
    }

    /** 199개(전부 10000) + 마지막(전일) 11000 → SMA200 초과라 ON. */
    private List<Candle> regimeOnIndexCandles() {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 199; i++) {
            candles.add(candle("069500", i, "10000"));
        }
        candles.add(candle("069500", 199, "11000"));
        return candles;
    }

    /** 199개(전부 10000) + 마지막(전일) 9000 → SMA200 이하라 OFF. */
    private List<Candle> regimeOffIndexCandles() {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 199; i++) {
            candles.add(candle("069500", i, "10000"));
        }
        candles.add(candle("069500", 199, "9000"));
        return candles;
    }

    /** lookbackN=5 기준 상승추세 — 21개 종가, 마지막(어제)=10500 > 6번째전(N=5, index15)=10000. */
    private List<Candle> uptrendSymbolCandles(String symbol) {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            candles.add(candle(symbol, i, "10000"));
        }
        candles.add(candle(symbol, 20, "10500"));
        return candles;
    }

    /** lookbackN=5 기준 하락추세 — 마지막(어제)=9500 < N봉전(index15)=10000. */
    private List<Candle> downtrendSymbolCandles(String symbol) {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            candles.add(candle(symbol, i, "10000"));
        }
        candles.add(candle(symbol, 20, "9500"));
        return candles;
    }

    private C3StrategyProperties properties(boolean enabled, List<String> symbols, int lookbackN) {
        return new C3StrategyProperties(enabled, symbols, lookbackN, 21, 0.20, "069500", 200);
    }

    @Test
    void enabled가_false면_아무_시그널도_발행하지_않는다() {
        StubChartService chart = new StubChartService();
        PositionBook positionBook = new PositionBook();
        List<Object> published = new ArrayList<>();
        C3LiveStrategy strategy = new C3LiveStrategy(
                properties(false, List.of("005930"), 5), chart, positionBook, published::add, marketCalendarService);

        strategy.run();

        assertTrue(published.isEmpty(), "enabled=false면 no-op이어야 함(시그널·조회 모두 없음)");
    }

    @Test
    void 국면이_OFF면_보유중인_심볼_전부_SELL만_발행하고_신규진입은_막는다() {
        StubChartService chart = new StubChartService();
        chart.put("069500", regimeOffIndexCandles());
        // 미보유 종목(005930)이 상승추세로 세팅돼 있어도 OFF면 아예 판단 대상이 아니어야 한다.
        chart.put("005930", uptrendSymbolCandles("005930"));

        PositionBook positionBook = new PositionBook();
        positionBook.onFill(new Fill("k1", "b1", "000660", Side.BUY, 10, new BigDecimal("50000"), Instant.now()));

        List<Object> published = new ArrayList<>();
        C3LiveStrategy strategy = new C3LiveStrategy(
                properties(true, List.of("005930", "000660"), 5), chart, positionBook, published::add, marketCalendarService);

        strategy.run();

        assertEquals(1, published.size(), "보유 중인 000660만 SELL 시그널 1건이어야 함(005930 진입 없음)");
        Signal signal = (Signal) published.get(0);
        assertEquals("000660", signal.symbol());
        assertEquals(Side.SELL, signal.side());
        assertEquals("C3-MOMENTUM", signal.strategyId());
    }

    @Test
    void 국면_ON_상승추세_신규진입이면_BUY_confidence는_투입비중fraction이다() {
        StubChartService chart = new StubChartService();
        chart.put("069500", regimeOnIndexCandles());
        chart.put("005930", uptrendSymbolCandles("005930"));

        PositionBook positionBook = new PositionBook(); // 미보유
        List<Object> published = new ArrayList<>();
        C3LiveStrategy strategy = new C3LiveStrategy(
                properties(true, List.of("005930"), 5), chart, positionBook, published::add, marketCalendarService);

        strategy.run();

        assertEquals(1, published.size());
        Signal signal = (Signal) published.get(0);
        assertEquals("005930", signal.symbol());
        assertEquals(Side.BUY, signal.side());
        assertTrue(signal.confidence() > 0.0 && signal.confidence() <= 1.0,
                "confidence(fraction)는 (0,1] 구간이어야 함: " + signal.confidence());
    }

    @Test
    void 국면_ON_하락추세_보유중이면_SELL을_발행한다() {
        StubChartService chart = new StubChartService();
        chart.put("069500", regimeOnIndexCandles());
        chart.put("005930", downtrendSymbolCandles("005930"));

        PositionBook positionBook = new PositionBook();
        positionBook.onFill(new Fill("k1", "b1", "005930", Side.BUY, 10, new BigDecimal("10000"), Instant.now()));

        List<Object> published = new ArrayList<>();
        C3LiveStrategy strategy = new C3LiveStrategy(
                properties(true, List.of("005930"), 5), chart, positionBook, published::add, marketCalendarService);

        strategy.run();

        assertEquals(1, published.size());
        Signal signal = (Signal) published.get(0);
        assertEquals("005930", signal.symbol());
        assertEquals(Side.SELL, signal.side());
    }

    @Test
    void 국면_ON_상승추세_이미_보유중이면_추가_매수하지_않는다() {
        StubChartService chart = new StubChartService();
        chart.put("069500", regimeOnIndexCandles());
        chart.put("005930", uptrendSymbolCandles("005930"));

        PositionBook positionBook = new PositionBook();
        positionBook.onFill(new Fill("k1", "b1", "005930", Side.BUY, 10, new BigDecimal("10000"), Instant.now()));

        List<Object> published = new ArrayList<>();
        C3LiveStrategy strategy = new C3LiveStrategy(
                properties(true, List.of("005930"), 5), chart, positionBook, published::add, marketCalendarService);

        strategy.run();

        assertTrue(published.isEmpty(), "상승추세라도 이미 보유 중이면 추가 매수(재진입)하면 안 됨");
    }

    @Test
    void 한_종목_조회_실패는_다른_종목_판단을_막지_않는다() {
        StubChartService chart = new StubChartService();
        chart.put("069500", regimeOnIndexCandles());
        chart.failFor("005930", new RuntimeException("네트워크 오류(테스트)"));
        chart.put("000660", uptrendSymbolCandles("000660"));

        PositionBook positionBook = new PositionBook();
        List<Object> published = new ArrayList<>();
        C3LiveStrategy strategy = new C3LiveStrategy(
                properties(true, List.of("005930", "000660"), 5), chart, positionBook, published::add, marketCalendarService);

        assertDoesNotThrow(strategy::run, "한 종목의 예외가 전체 배치 실행을 중단시키면 안 됨");

        assertEquals(1, published.size(), "실패한 005930은 스킵되고 000660만 판단돼야 함");
        Signal signal = (Signal) published.get(0);
        assertEquals("000660", signal.symbol());
    }
}

package com.autostock.strategy;

import com.autostock.common.event.Candle;
import com.autostock.common.event.Fill;
import com.autostock.common.event.Side;
import com.autostock.common.event.Signal;
import com.autostock.common.event.SignalDecision;
import com.autostock.common.util.StockCode;
import com.autostock.common.util.StockNames;
import com.autostock.market.KiwoomDailyChartService;
import com.autostock.market.MarketCalendarService;
import com.autostock.market.MarketDataPort;
import com.autostock.market.MarketHolidayRepository;
import com.autostock.monitor.TradingSystemManager;
import com.autostock.monitor.TradingSystemStatus;
import com.autostock.portfolio.PositionBook;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C3LiveStrategy 검증 — market 서비스는 스텁으로 대체하고(실제 REST 호출 없음),
 * 국면 ON/OFF와 종목별 모멘텀에 따른 Signal 발행, 종목 단위 예외 격리, enabled=false
 * no-op을 확인한다.
 */
class C3LiveStrategyTest {

    private static final LocalDate D0 = LocalDate.of(2024, 1, 1);

    /**
     * 고정 시계 — 2026-10-01(목) 09:05 KST, 거래일. 실제 시계를 쓰면 주말·휴장일에 돌린 테스트가
     * "휴장일 스킵"에 걸려 실패한다(2026-10-01 실측: 날짜를 토요일로 속여 전체 테스트를 돌리면
     * 이 클래스 9건만 실패했다). 캔들은 스텁이 날짜를 보지 않으므로 이 시계와 무관하다.
     */
    private static final Clock OCT_1_0905 = Clock.fixed(Instant.parse("2026-10-01T00:05:00Z"), ZoneOffset.UTC);

    // repository는 mock — market_holidays에 해당 연도 데이터가 없으므로 MarketCalendarService는
    // TradingCalendar 하드코딩 폴백으로 판정한다(기존 동작과 동일, RiskGateTest와 같은 이유).
    private final MarketCalendarService marketCalendarService = new MarketCalendarService(mock(MarketHolidayRepository.class));

    /** 시세 조회 포트 — 강제 청산 기준가(최우선 매수호가) 조회용. 스텁하지 않은 조회는 null → "호가 없음"으로 처리된다. */
    private final MarketDataPort marketData = mock(MarketDataPort.class);

    /**
     * 운영 상태기계가 RUNNING이라고 가정하는 스텁 — 이 테스트 파일은 C3LiveStrategy의
     * 매매 판단 로직만 검증하므로, 이중 가드(클래스 설명 "운영 상태기계와의 이중 가드" 참고)
     * 자체는 여기서 항상 통과시킨다(스킵 동작은 별도 테스트에서 확인).
     */
    private TradingSystemManager runningManager() {
        TradingSystemManager manager = mock(TradingSystemManager.class);
        when(manager.status()).thenReturn(TradingSystemStatus.RUNNING);
        return manager;
    }

    /**
     * FE-6(SignalDecision)부터는 이 전략이 Signal뿐 아니라 판단 근거(SignalDecision)도 같은
     * publisher로 발행한다 — 기존 테스트들이 "published.size()==Signal 개수"를 가정하므로,
     * Signal만 걸러서 기존 단언 의도를 그대로 유지한다.
     */
    private static List<Signal> onlySignals(List<Object> published) {
        return published.stream().filter(Signal.class::isInstance).map(Signal.class::cast).toList();
    }

    private static List<SignalDecision> onlyDecisions(List<Object> published) {
        return published.stream().filter(SignalDecision.class::isInstance).map(SignalDecision.class::cast).toList();
    }

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
                properties(false, List.of("005930"), 5), chart, marketData, positionBook, published::add, marketCalendarService,
                mock(TradingSystemManager.class), OCT_1_0905); // enabled=false는 상태 조회 전에 반환되므로 스텁 불필요

        strategy.run();

        assertTrue(published.isEmpty(), "enabled=false면 no-op이어야 함(시그널·조회 모두 없음)");
    }

    @Test
    void 운영_상태가_RUNNING이_아니면_enabled여도_스킵한다() {
        // 이중 가드 검증 — strategy.c3.enabled=true여도 TradingSystemManager.status()가
        // RUNNING이 아니면(예: STARTING) 매매 판단 자체를 시작하지 않아야 한다.
        StubChartService chart = new StubChartService();
        chart.put("069500", regimeOnIndexCandles());
        chart.put("005930", uptrendSymbolCandles("005930"));

        PositionBook positionBook = new PositionBook();
        List<Object> published = new ArrayList<>();
        TradingSystemManager notRunning = mock(TradingSystemManager.class);
        when(notRunning.status()).thenReturn(TradingSystemStatus.STARTING);

        C3LiveStrategy strategy = new C3LiveStrategy(
                properties(true, List.of("005930"), 5), chart, marketData, positionBook, published::add, marketCalendarService,
                notRunning, OCT_1_0905);

        strategy.run();

        assertTrue(published.isEmpty(), "운영 상태가 RUNNING이 아니면 이중 가드로 스킵돼야 함");
    }

    @Test
    void 국면이_OFF면_보유중인_심볼_전부_SELL만_발행하고_신규진입은_막는다() {
        StubChartService chart = new StubChartService();
        chart.put("069500", regimeOffIndexCandles());
        // 미보유 종목(005930)이 상승추세로 세팅돼 있어도 OFF면 아예 판단 대상이 아니어야 한다.
        chart.put("005930", uptrendSymbolCandles("005930"));

        PositionBook positionBook = new PositionBook();
        positionBook.onFill(new Fill("k1", new BrokerOrderId("b1"), new StockCode("000660"), Side.BUY, new Quantity(10), new Price(new BigDecimal("50000")), Instant.now()));

        List<Object> published = new ArrayList<>();
        C3LiveStrategy strategy = new C3LiveStrategy(
                properties(true, List.of("005930", "000660"), 5), chart, marketData, positionBook, published::add, marketCalendarService,
                runningManager(), OCT_1_0905);

        when(marketData.bestQuote("000660")).thenReturn(new MarketDataPort.BestQuote(
                new BigDecimal("49550"), new BigDecimal("49500")));

        strategy.run();

        List<Signal> signals = onlySignals(published);
        assertEquals(1, signals.size(), "보유 중인 000660만 SELL 시그널 1건이어야 함(005930 진입 없음)");
        Signal signal = signals.get(0);
        assertEquals(new StockCode("000660"), signal.symbol());
        assertEquals(Side.SELL, signal.side());
        assertEquals("C3-MOMENTUM", signal.strategyId());
        // 기준가는 평균매입가(50000)가 아니라 주문 시점 최우선 매수호가 — 손실 구간에서도 체결되는 매도 지정가(조각 20)
        assertEquals(new Price(new BigDecimal("49500")), signal.refPrice());
    }

    @Test
    void 강제_청산_호가가_없으면_현재가_둘_다_없으면_기준가_없이_신호를_내고_다른_종목은_계속한다() {
        StubChartService chart = new StubChartService();
        chart.put("069500", regimeOffIndexCandles());
        PositionBook positionBook = new PositionBook();
        positionBook.onFill(new Fill("k1", new BrokerOrderId("b1"), new StockCode("005930"), Side.BUY, new Quantity(10), new Price(new BigDecimal("70000")), Instant.now()));
        positionBook.onFill(new Fill("k2", new BrokerOrderId("b2"), new StockCode("000660"), Side.BUY, new Quantity(10), new Price(new BigDecimal("50000")), Instant.now()));
        when(marketData.bestQuote("005930")).thenThrow(new RuntimeException("ka10004 실패"));
        when(marketData.stockQuote("005930")).thenReturn(new MarketDataPort.StockQuote("삼성전자", new BigDecimal("68000"), null, null, null));
        when(marketData.bestQuote("000660")).thenReturn(MarketDataPort.BestQuote.EMPTY);
        when(marketData.stockQuote("000660")).thenReturn(MarketDataPort.StockQuote.EMPTY);

        List<Object> published = new ArrayList<>();
        C3LiveStrategy strategy = new C3LiveStrategy(
                properties(true, List.of("005930", "000660"), 5), chart, marketData, positionBook, published::add, marketCalendarService,
                runningManager(), OCT_1_0905);

        strategy.run();

        List<Signal> signals = onlySignals(published);
        assertEquals(2, signals.size());
        assertEquals(new Price(new BigDecimal("68000")), signals.get(0).refPrice(), "호가 조회 실패 → 현재가");
        assertNull(signals.get(1).refPrice(), "호가·현재가 모두 없음 → 기준가 없음(RiskGate가 거부)");
    }

    @Test
    void 국면_ON_상승추세_신규진입이면_BUY_confidence는_투입비중fraction이다() {
        StubChartService chart = new StubChartService();
        chart.put("069500", regimeOnIndexCandles());
        chart.put("005930", uptrendSymbolCandles("005930"));

        PositionBook positionBook = new PositionBook(); // 미보유
        List<Object> published = new ArrayList<>();
        C3LiveStrategy strategy = new C3LiveStrategy(
                properties(true, List.of("005930"), 5), chart, marketData, positionBook, published::add, marketCalendarService,
                runningManager(), OCT_1_0905);

        when(marketData.bestQuote("005930")).thenReturn(new MarketDataPort.BestQuote(
                new BigDecimal("10100"), new BigDecimal("10050")));

        strategy.run();

        List<Signal> signals = onlySignals(published);
        assertEquals(1, signals.size());
        Signal signal = signals.get(0);
        assertEquals(new StockCode("005930"), signal.symbol());
        assertEquals(Side.BUY, signal.side());
        assertTrue(signal.confidence() > 0.0 && signal.confidence() <= 1.0,
                "confidence(fraction)는 (0,1] 구간이어야 함: " + signal.confidence());
        // 기준가는 전일 종가가 아니라 주문 시점 최유리 호가 — 매수는 최우선 매도호가(사용자 결정 2026-09-30, 조각 22)
        assertEquals(new Price(new BigDecimal("10100")), signal.refPrice());
    }

    @Test
    void 국면_ON_하락추세_보유중이면_SELL을_발행한다() {
        StubChartService chart = new StubChartService();
        chart.put("069500", regimeOnIndexCandles());
        chart.put("005930", downtrendSymbolCandles("005930"));

        PositionBook positionBook = new PositionBook();
        positionBook.onFill(new Fill("k1", new BrokerOrderId("b1"), new StockCode("005930"), Side.BUY, new Quantity(10), new Price(new BigDecimal("10000")), Instant.now()));

        List<Object> published = new ArrayList<>();
        C3LiveStrategy strategy = new C3LiveStrategy(
                properties(true, List.of("005930"), 5), chart, marketData, positionBook, published::add, marketCalendarService,
                runningManager(), OCT_1_0905);

        when(marketData.bestQuote("005930")).thenReturn(new MarketDataPort.BestQuote(
                new BigDecimal("9100"), new BigDecimal("9050")));

        strategy.run();

        List<Signal> signals = onlySignals(published);
        assertEquals(1, signals.size());
        Signal signal = signals.get(0);
        assertEquals(new StockCode("005930"), signal.symbol());
        assertEquals(Side.SELL, signal.side());
        assertEquals(new Price(new BigDecimal("9050")), signal.refPrice(), "매도는 최우선 매수호가");
    }

    @Test
    void 국면_ON_상승추세_이미_보유중이면_추가_매수하지_않는다() {
        StubChartService chart = new StubChartService();
        chart.put("069500", regimeOnIndexCandles());
        chart.put("005930", uptrendSymbolCandles("005930"));

        PositionBook positionBook = new PositionBook();
        positionBook.onFill(new Fill("k1", new BrokerOrderId("b1"), new StockCode("005930"), Side.BUY, new Quantity(10), new Price(new BigDecimal("10000")), Instant.now()));

        List<Object> published = new ArrayList<>();
        C3LiveStrategy strategy = new C3LiveStrategy(
                properties(true, List.of("005930"), 5), chart, marketData, positionBook, published::add, marketCalendarService,
                runningManager(), OCT_1_0905);

        strategy.run();

        assertTrue(onlySignals(published).isEmpty(), "상승추세라도 이미 보유 중이면 추가 매수(재진입)하면 안 됨");
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
                properties(true, List.of("005930", "000660"), 5), chart, marketData, positionBook, published::add, marketCalendarService,
                runningManager(), OCT_1_0905);

        assertDoesNotThrow(strategy::run, "한 종목의 예외가 전체 배치 실행을 중단시키면 안 됨");

        List<Signal> signals = onlySignals(published);
        assertEquals(1, signals.size(), "실패한 005930은 스킵되고 000660만 판단돼야 함");
        Signal signal = signals.get(0);
        assertEquals(new StockCode("000660"), signal.symbol());
    }

    // ── FE-6: SignalDecision 발행 검증 — 순수 함수(MomentumMath/RegimeMath/VolTargetMath)의
    // 계산 결과를 shell(C3LiveStrategy)이 SignalDecision 이벤트로 올바르게 변환하는지만 본다
    // (Math 클래스 자체는 위 테스트들과 동일한 스텁 데이터를 그대로 재사용 — 백테스트 수치에
    // 영향을 주는 코드는 건드리지 않는다).

    @Test
    void 국면_ON_상승추세_신규진입_BUY_결정에_모멘텀_국면_볼타겟_지표가_모두_담긴다() {
        StubChartService chart = new StubChartService();
        chart.put("069500", regimeOnIndexCandles());
        chart.put("005930", uptrendSymbolCandles("005930"));

        PositionBook positionBook = new PositionBook();
        List<Object> published = new ArrayList<>();
        C3LiveStrategy strategy = new C3LiveStrategy(
                properties(true, List.of("005930"), 5), chart, marketData, positionBook, published::add, marketCalendarService,
                runningManager(), OCT_1_0905);

        strategy.run();

        List<SignalDecision> decisions = onlyDecisions(published);
        assertEquals(1, decisions.size());
        SignalDecision decision = decisions.get(0);
        assertEquals("MID", decision.horizon());
        assertEquals("C3-MOMENTUM", decision.strategyId());
        assertEquals(new StockCode("005930"), decision.symbol());
        assertEquals("BUY", decision.conclusion());
        assertEquals("ON", decision.metrics().get("regimeStatus"));
        assertEquals("5", decision.metrics().get("momentumLookbackN"));
        assertTrue(decision.metrics().containsKey("momentumReturnPct"));
        assertTrue(decision.metrics().containsKey("volTargetFraction"),
                "BUY 결정에는 VolTargetMath가 산출한 투입 비중이 지표로 남아야 함");

        // Signal.confidence()와 SignalDecision.metrics()의 volTargetFraction은 같은
        // VolTargetMath.fraction() 호출 결과를 실은 것이므로 값이 일치해야 한다.
        Signal signal = onlySignals(published).get(0);
        assertEquals(String.valueOf(signal.confidence()), decision.metrics().get("volTargetFraction"));
    }

    @Test
    void 국면_OFF면_판단_대상_전체_종목에_SKIP_결정이_남는다() {
        StubChartService chart = new StubChartService();
        chart.put("069500", regimeOffIndexCandles());
        chart.put("005930", uptrendSymbolCandles("005930"));

        PositionBook positionBook = new PositionBook();
        List<Object> published = new ArrayList<>();
        C3LiveStrategy strategy = new C3LiveStrategy(
                properties(true, List.of("005930", "000660"), 5), chart, marketData, positionBook, published::add, marketCalendarService,
                runningManager(), OCT_1_0905);

        strategy.run();

        List<SignalDecision> decisions = onlyDecisions(published);
        assertEquals(2, decisions.size(), "국면 OFF — 판단 대상 두 종목 모두 SKIP 결정이 남아야 함");
        for (SignalDecision decision : decisions) {
            assertEquals("SKIP", decision.conclusion());
            assertEquals("OFF", decision.metrics().get("regimeStatus"));
            assertTrue(decision.reason().contains("국면 OFF"));
        }
    }

    @Test
    void 국면_ON_하락추세_미보유면_SKIP_결정이_남는다() {
        StubChartService chart = new StubChartService();
        chart.put("069500", regimeOnIndexCandles());
        chart.put("005930", downtrendSymbolCandles("005930"));

        PositionBook positionBook = new PositionBook(); // 미보유
        List<Object> published = new ArrayList<>();
        C3LiveStrategy strategy = new C3LiveStrategy(
                properties(true, List.of("005930"), 5), chart, marketData, positionBook, published::add, marketCalendarService,
                runningManager(), OCT_1_0905);

        strategy.run();

        assertTrue(onlySignals(published).isEmpty(), "하락추세+미보유는 Signal을 내지 않아야 함");
        List<SignalDecision> decisions = onlyDecisions(published);
        assertEquals(1, decisions.size());
        assertEquals("SKIP", decisions.get(0).conclusion());
        assertEquals(new StockCode("005930"), decisions.get(0).symbol());
    }

    @Test
    void 국면_ON_상승추세_이미_보유중이면_HOLD_결정이_남는다() {
        StubChartService chart = new StubChartService();
        chart.put("069500", regimeOnIndexCandles());
        chart.put("005930", uptrendSymbolCandles("005930"));

        PositionBook positionBook = new PositionBook();
        positionBook.onFill(new Fill("k1", new BrokerOrderId("b1"), new StockCode("005930"), Side.BUY, new Quantity(10), new Price(new BigDecimal("10000")), Instant.now()));

        List<Object> published = new ArrayList<>();
        C3LiveStrategy strategy = new C3LiveStrategy(
                properties(true, List.of("005930"), 5), chart, marketData, positionBook, published::add, marketCalendarService,
                runningManager(), OCT_1_0905);

        strategy.run();

        List<SignalDecision> decisions = onlyDecisions(published);
        assertEquals(1, decisions.size());
        assertEquals("HOLD", decisions.get(0).conclusion());
    }

    // ── 실행 요약(2026-10-01 로그 점검 F-2, aiDoc/run-summary-logs.md) ─────────────────────
    // 판단 결과는 DB에만 남으므로 run()이 요약 1줄을 로그로 남긴다 — 그 집계(execute())를 검증한다.

    private static Fill buyFill(String symbol) {
        return new Fill("k-" + symbol, new BrokerOrderId("b-" + symbol), new StockCode(symbol), Side.BUY,
                new Quantity(10), new Price(new BigDecimal("10000")), Instant.parse("2026-09-30T01:00:00Z"));
    }

    @Test
    void 국면_ON_요약은_결과별_건수_오류_다음_판단일을_담는다() {
        StockNames.learn("069500", "KODEX 200", StockNames.Source.KIWOOM); // 요약의 국면 지수도 "이름(코드)"로 남긴다
        StubChartService chart = new StubChartService();
        chart.put("069500", regimeOnIndexCandles());
        chart.put("005930", uptrendSymbolCandles("005930"));   // 미보유 상승 → 매수
        chart.put("000660", downtrendSymbolCandles("000660")); // 보유 하락 → 매도
        chart.put("035420", uptrendSymbolCandles("035420"));   // 보유 상승 → 보유 유지
        chart.put("035720", downtrendSymbolCandles("035720")); // 미보유 하락 → 미진입
        chart.failFor("051910", new RuntimeException("네트워크 오류(테스트)")); // 오류 → 다음 스케줄 재판단

        PositionBook positionBook = new PositionBook();
        positionBook.onFill(buyFill("000660"));
        positionBook.onFill(buyFill("035420"));
        List<Object> published = new ArrayList<>();
        C3LiveStrategy strategy = new C3LiveStrategy(
                properties(true, List.of("005930", "000660", "035420", "035720", "051910"), 5), chart, marketData,
                positionBook, published::add, marketCalendarService, runningManager(), OCT_1_0905);

        C3LiveStrategy.RunSummary summary = strategy.execute();

        assertTrue(summary.regimeOn());
        assertEquals(1, summary.count(C3LiveStrategy.Outcome.BUY));
        assertEquals(1, summary.count(C3LiveStrategy.Outcome.SELL));
        assertEquals(1, summary.count(C3LiveStrategy.Outcome.HOLD));
        assertEquals(1, summary.count(C3LiveStrategy.Outcome.SKIP));
        assertEquals(1, summary.errors());
        assertEquals(LocalDate.of(2026, 10, 22), summary.nextDue(), "판단한 종목은 10/1 + 21일");
        assertEquals(1, summary.retrySymbols(), "실패한 051910은 다음 스케줄에 다시 판단");
        assertEquals("C3 판단 2026-10-01 — 국면 ON(KODEX 200(069500) 종가 11000 / SMA200 10005)"
                        + " · 매수 1 · 매도 1 · 보유 유지 1 · 미진입 1 · 주기 전 0 · 데이터 부족 0 · 오류 1"
                        + " · 다음 판단 2026-10-22부터 · 다음 스케줄에 재판단 1종목",
                summary.toLogLine());
    }

    @Test
    void 같은_날_다시_돌면_판단한_종목은_주기_전으로_센다() {
        StubChartService chart = new StubChartService();
        chart.put("069500", regimeOnIndexCandles());
        chart.put("035720", downtrendSymbolCandles("035720"));
        List<Object> published = new ArrayList<>();
        C3LiveStrategy strategy = new C3LiveStrategy(
                properties(true, List.of("035720"), 5), chart, marketData, new PositionBook(), published::add,
                marketCalendarService, runningManager(), OCT_1_0905);

        strategy.execute();
        C3LiveStrategy.RunSummary second = strategy.execute();

        assertEquals(1, second.count(C3LiveStrategy.Outcome.NOT_DUE));
        assertEquals(0, second.retrySymbols());
    }

    @Test
    void 캔들이_부족하면_데이터_부족으로_세고_다음_스케줄에_재판단한다() {
        StubChartService chart = new StubChartService();
        chart.put("069500", regimeOnIndexCandles()); // 005930은 캔들 없음
        List<Object> published = new ArrayList<>();
        C3LiveStrategy strategy = new C3LiveStrategy(
                properties(true, List.of("005930"), 5), chart, marketData, new PositionBook(), published::add,
                marketCalendarService, runningManager(), OCT_1_0905);

        C3LiveStrategy.RunSummary summary = strategy.execute();

        assertEquals(1, summary.count(C3LiveStrategy.Outcome.NO_DATA));
        assertNull(summary.nextDue());
        assertTrue(summary.toLogLine().endsWith("· 오류 0 · 다음 스케줄에 재판단 1종목"), summary.toLogLine());
    }

    @Test
    void 국면_OFF_요약은_강제_청산_신호_수를_담는다() {
        StubChartService chart = new StubChartService();
        chart.put("069500", regimeOffIndexCandles());
        PositionBook positionBook = new PositionBook();
        positionBook.onFill(buyFill("005930"));
        List<Object> published = new ArrayList<>();
        C3LiveStrategy strategy = new C3LiveStrategy(
                properties(true, List.of("005930", "000660"), 5), chart, marketData, positionBook, published::add,
                marketCalendarService, runningManager(), OCT_1_0905);

        C3LiveStrategy.RunSummary summary = strategy.execute();

        assertEquals(false, summary.regimeOn());
        assertEquals(1, summary.liquidations());
        // 이름을 모르는 종목도 코드와 함께 "종목명 미확인(코드)"로 남긴다
        assertEquals("C3 판단 2026-10-01 — 국면 OFF(종목명 미확인(069500) 종가 9000 / SMA200 9995) · 신규 판단 보류 · 강제 청산 신호 1 · 오류 0",
                summary.toLogLine());
    }

    @Test
    void 비활성이거나_휴장일이면_요약이_없다() {
        StubChartService chart = new StubChartService();
        chart.put("069500", regimeOnIndexCandles());
        List<Object> published = new ArrayList<>();
        C3LiveStrategy disabled = new C3LiveStrategy(
                properties(false, List.of("005930"), 5), chart, marketData, new PositionBook(), published::add,
                marketCalendarService, runningManager(), OCT_1_0905);
        // 2026-10-05(월) — 개천절 대체공휴일(폴백 달력)
        C3LiveStrategy holiday = new C3LiveStrategy(
                properties(true, List.of("005930"), 5), chart, marketData, new PositionBook(), published::add,
                marketCalendarService, runningManager(),
                Clock.fixed(Instant.parse("2026-10-05T00:05:00Z"), ZoneOffset.UTC));

        assertNull(disabled.execute());
        assertNull(holiday.execute());
        assertTrue(published.isEmpty());
    }
}

package com.autostock.risk;

import com.autostock.common.event.Fill;
import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Side;
import com.autostock.common.event.Signal;
import com.autostock.common.event.SignalDecision;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.ClientOrderId;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import com.autostock.common.util.StockCode;
import com.autostock.market.MarketCalendarService;
import com.autostock.market.MarketHolidayRepository;
import com.autostock.portfolio.PositionBook;
import com.autostock.portfolio.StrategyBook;
import com.autostock.portfolio.StrategyFillRepository;
import com.autostock.portfolio.StrategyPositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 운용 자금 한도(2026-10-21, aiDoc/multi-strategy-paper-2026-10-16.md 8절) — 사이징 기준 min(계좌, 운용 자금),
 * 계좌 보유 매수 원가 + 미체결 매수 + 새 주문 ≤ 운용 자금, 예산으로 0주일 때 1주 허용(예산의 2배까지).
 * 계좌 평가액은 1억(PaperEquitySource), 운용 자금은 2,000만 원.
 */
class RiskGateCapitalTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T01:00:00Z"), ZoneOffset.UTC);
    private static final TradingCapitalProperties CAPITAL_20M = new TradingCapitalProperties(20_000_000, 2.0);

    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher publisher = published::add;
    private BigDecimal pendingBuyAmount = BigDecimal.ZERO;
    private RuntimeException pendingFailure;
    private final OpenOrderQuery openOrders = new OpenOrderQuery() {
        @Override
        public Set<StockCode> symbolsWithOpenBuy() {
            return Set.of();
        }

        @Override
        public Set<StockCode> symbolsWithOpenOrder(String strategyId, Side side) {
            return Set.of();
        }

        @Override
        public BigDecimal openBuyAmount() {
            if (pendingFailure != null) {
                throw pendingFailure;
            }
            return pendingBuyAmount;
        }
    };

    private KillSwitch killSwitch;
    private PositionBook positionBook;
    private StrategyBook strategyBook;
    private RiskProperties properties;
    private int seq;

    @BeforeEach
    void setUp() {
        properties = new RiskProperties(0.10, 40, -0.03, 0.05, -0.05, 150, 100_000_000, 0.00015, 0.0020, false,
                1_000_000);
        killSwitch = new KillSwitch(event -> { }, mock(RiskStateStore.class), Clock.systemUTC());
        positionBook = new PositionBook();
        StrategyPositionRepository positions = mock(StrategyPositionRepository.class);
        when(positions.findAll()).thenReturn(List.of());
        strategyBook = new StrategyBook(positions, mock(StrategyFillRepository.class), CLOCK, 0.00015, 0.0020);
    }

    private RiskGate gate(TradingCapitalProperties capital) {
        MultiStrategyRiskProperties multi = new MultiStrategyRiskProperties(true, List.of(
                new MultiStrategyRiskProperties.Sleeve("C3-MOMENTUM", 0.50, 5),
                new MultiStrategyRiskProperties.Sleeve("VB-DAY", 0.15, 3),
                new MultiStrategyRiskProperties.Sleeve("TA-H1", 0.04, 5)));
        MacroGuard macroGuard = new MacroGuard(RiskGateTest.defaultMacroIntelProperties(), killSwitch, event -> { },
                Clock.systemUTC());
        DisclosureBlacklist blacklist = new DisclosureBlacklist(mock(DisclosureBlacklistRepository.class), publisher, CLOCK);
        return new RiskGate(publisher, killSwitch, properties, new PositionSizer(properties), positionBook,
                new DailyLimitTracker(properties, CLOCK), new PaperEquitySource(properties), CLOCK,
                new MarketCalendarService(mock(MarketHolidayRepository.class)), macroGuard, blacklist, openOrders,
                strategyBook, multi, capital);
    }

    private void filled(String strategyId, String symbol, Side side, long qty, String price) {
        String id = ClientOrderId.generate(LocalDate.of(2026, 10, 22), strategyId, symbol, side, ++seq).value();
        Fill fill = new Fill(id, new BrokerOrderId("B" + seq), new StockCode(symbol), side, new Quantity(qty),
                new Price(new BigDecimal(price)), CLOCK.instant());
        positionBook.onFill(fill);
        strategyBook.onFill(fill);
    }

    private static Signal signal(String strategyId, String symbol, Side side, String price) {
        return new Signal(strategyId, new StockCode(symbol), side, new Price(new BigDecimal(price)), 1.0, Instant.now());
    }

    private List<OrderRequest> orders() {
        return published.stream().filter(OrderRequest.class::isInstance).map(OrderRequest.class::cast).toList();
    }

    private List<String> rejections() {
        return published.stream().filter(SignalDecision.class::isInstance).map(SignalDecision.class::cast)
                .map(SignalDecision::reason).toList();
    }

    @Test
    void 운용_자금이_사이징_기준이다() {
        // min(1억, 2,000만) × 15% ÷ 3 = 100만 원 ÷ 10만 원 = 10주
        gate(CAPITAL_20M).onSignal(signal("VB-DAY", "005930", Side.BUY, "100000"));
        assertEquals(new Quantity(10), orders().get(0).quantity());
    }

    @Test
    void 운용_자금이_꺼져_있으면_계좌_평가액_기준이다() {
        // 1억 × 15% ÷ 3 = 500만 원 ÷ 10만 원 = 50주(예전과 같다)
        gate(TradingCapitalProperties.none()).onSignal(signal("VB-DAY", "005930", Side.BUY, "100000"));
        assertEquals(new Quantity(50), orders().get(0).quantity());
    }

    @Test
    void 예산보다_비싼_종목은_1주_값이_예산의_2배_이내면_1주를_산다() {
        // 차트 기법 2,000만 × 4% ÷ 5 = 16만 원. 30만 원짜리는 0주 → 32만 원 이내라 1주
        gate(CAPITAL_20M).onSignal(signal("TA-H1", "000660", Side.BUY, "300000"));
        assertEquals(new Quantity(1), orders().get(0).quantity());
    }

    @Test
    void 예산의_2배를_넘는_1주는_사지_않는다() {
        gate(CAPITAL_20M).onSignal(signal("TA-H1", "000660", Side.BUY, "330000"));
        assertTrue(orders().isEmpty());
        assertTrue(rejections().contains("사이징 결과 0주 — 매수 불가"));
    }

    @Test
    void 보유_원가와_미체결_매수를_합쳐_운용_자금을_넘지_않게_수량을_줄인다() {
        filled("VB-DAY", "000660", Side.BUY, 150, "100000");   // 보유 원가 1,500만
        pendingBuyAmount = new BigDecimal("4000000");          // 미체결 매수 400만 → 여유 100만

        // C3: 2,000만 × 50% ÷ 5 = 200만 원 → 20주였지만 여유 100만 원이라 10주
        gate(CAPITAL_20M).onSignal(signal("C3-MOMENTUM", "005930", Side.BUY, "100000"));

        assertEquals(new Quantity(10), orders().get(0).quantity());
    }

    @Test
    void 여유가_없으면_매수를_거부한다() {
        filled("VB-DAY", "000660", Side.BUY, 150, "100000");
        pendingBuyAmount = new BigDecimal("5000000");          // 1,500만 + 500만 = 2,000만

        gate(CAPITAL_20M).onSignal(signal("C3-MOMENTUM", "005930", Side.BUY, "100000"));

        assertTrue(orders().isEmpty());
        assertTrue(rejections().contains("운용 자금 한도 — 매수 거부"));
    }

    @Test
    void 운용_자금보다_큰_예전_보유가_있으면_새로_사지_않는다() {
        // 10/21 종가 매수(약 6,800만 원)처럼 운용 자금 전에 산 보유 — 정리되기 전까지 새 매수는 막힌다
        filled("ON-CLOSE", "000660", Side.BUY, 50, "1300000");

        gate(CAPITAL_20M).onSignal(signal("VB-DAY", "005930", Side.BUY, "100000"));

        assertTrue(orders().isEmpty());
        assertTrue(rejections().contains("운용 자금 한도 — 매수 거부"));
    }

    @Test
    void 미체결_매수_금액을_모르면_사지_않는다() {
        pendingFailure = new IllegalStateException("db down");

        gate(CAPITAL_20M).onSignal(signal("VB-DAY", "005930", Side.BUY, "100000"));

        assertTrue(orders().isEmpty());
        assertTrue(rejections().contains("미체결 매수 금액 조회 실패 — 매수 거부"));
    }

    @Test
    void 매도는_운용_자금과_무관하다() {
        filled("VB-DAY", "000660", Side.BUY, 300, "100000");   // 3,000만 — 운용 자금보다 많다

        gate(CAPITAL_20M).onSignal(signal("VB-DAY", "000660", Side.SELL, "99000"));

        assertEquals(new Quantity(300), orders().get(0).quantity());
    }

    @Test
    void 운용_자금은_0_또는_200만에서_2000만_원만_받는다() {
        assertDoesNotThrow(() -> new TradingCapitalProperties(0, 2.0));
        assertDoesNotThrow(() -> new TradingCapitalProperties(2_000_000, 2.0));
        assertDoesNotThrow(() -> new TradingCapitalProperties(20_000_000, 2.0));
        assertThrows(IllegalArgumentException.class, () -> new TradingCapitalProperties(1_999_999, 2.0));
        assertThrows(IllegalArgumentException.class, () -> new TradingCapitalProperties(200_000_000, 2.0));
        assertThrows(IllegalArgumentException.class, () -> new TradingCapitalProperties(20_000_000, -1.0));
    }
}

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 3분할 매수·최소 유의미 수량(2026-10-30, aiDoc/split-buy-swing-carry-2026-10-30.md). 계좌 1억, 운용 자금 200만 원,
 * 최소 유의미 수익 500원(수수료 제외 1%). VB-DAY 종목 예산 = 200만 × 15% ÷ 3 = 10만 원, 한 차수 = 33,333원,
 * 종목 원가 상한 = max(10만 × 2, 500 ÷ 1% × 2) = 20만 원.
 */
class RiskGateSplitBuyTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T01:00:00Z"), ZoneOffset.UTC);
    private static final TradingCapitalProperties CAPITAL = new TradingCapitalProperties(2_000_000, 2.0, 500, 0.01);

    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher publisher = published::add;
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
            return BigDecimal.ZERO;
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

    private RiskGate gate() {
        MultiStrategyRiskProperties multi = new MultiStrategyRiskProperties(true, List.of(
                new MultiStrategyRiskProperties.Sleeve("VB-DAY", 0.15, 3),
                new MultiStrategyRiskProperties.Sleeve("TA-H1", 0.04, 5)));
        MacroGuard macroGuard = new MacroGuard(RiskGateTest.defaultMacroIntelProperties(), killSwitch, event -> { },
                Clock.systemUTC());
        DisclosureBlacklist blacklist = new DisclosureBlacklist(mock(DisclosureBlacklistRepository.class), publisher, CLOCK);
        return new RiskGate(publisher, killSwitch, properties, new PositionSizer(properties), positionBook,
                new DailyLimitTracker(properties, CLOCK), new PaperEquitySource(properties), CLOCK,
                new MarketCalendarService(mock(MarketHolidayRepository.class)), macroGuard, blacklist, openOrders,
                strategyBook, multi, CAPITAL);
    }

    private void filled(String strategyId, String symbol, long qty, String price) {
        String id = ClientOrderId.generate(LocalDate.of(2026, 10, 30), strategyId, symbol, Side.BUY, ++seq).value();
        Fill fill = new Fill(id, new BrokerOrderId("B" + seq), new StockCode(symbol), Side.BUY, new Quantity(qty),
                new Price(new BigDecimal(price)), CLOCK.instant());
        positionBook.onFill(fill);
        strategyBook.onFill(fill);
    }

    private static Signal buy(String strategyId, String symbol, String price, Integer tranche) {
        return new Signal(strategyId, new StockCode(symbol), Side.BUY, new Price(new BigDecimal(price)), 1.0, null,
                Instant.now(), null, tranche);
    }

    private List<OrderRequest> orders() {
        return published.stream().filter(OrderRequest.class::isInstance).map(OrderRequest.class::cast).toList();
    }

    private List<String> rejections() {
        return published.stream().filter(SignalDecision.class::isInstance).map(SignalDecision.class::cast)
                .map(SignalDecision::reason).toList();
    }

    @Test
    void 최소_유의미_수량은_1퍼센트_수익이_500원을_넘는_가장_작은_수량이다() {
        assertEquals(51, CAPITAL.minMeaningfulQuantity(new BigDecimal("1000")));     // 51,000원 → 1% 510원
        assertEquals(6, CAPITAL.minMeaningfulQuantity(new BigDecimal("10000")));     // 50,000원은 500원 = 무의미
        assertEquals(2, CAPITAL.minMeaningfulQuantity(new BigDecimal("50000")));
        assertEquals(1, CAPITAL.minMeaningfulQuantity(new BigDecimal("50001")));
        assertEquals(0, new TradingCapitalProperties(2_000_000, 2.0).minMeaningfulQuantity(new BigDecimal("1000")));
        assertEquals(new BigDecimal("200000"), CAPITAL.meaningfulOrderCap(new BigDecimal("100000")).stripTrailingZeros()
                .setScale(0));
        assertEquals(new BigDecimal("100000"), CAPITAL.meaningfulOrderCap(new BigDecimal("16000")).setScale(0));
        assertThrows(IllegalArgumentException.class, () -> new TradingCapitalProperties(2_000_000, 2.0, -1, 0.01));
        assertThrows(IllegalArgumentException.class, () -> new TradingCapitalProperties(2_000_000, 2.0, 500, 0));
    }

    @Test
    void 일차_분할은_종목_예산의_3분의_1이고_무의미하면_최소_수량까지_올린다() {
        // 33,333원 ÷ 1만 원 = 3주(1% 수익 300원 — 무의미) → 6주
        gate().onSignal(buy("VB-DAY", "005930", "10000", 1));
        assertEquals(new Quantity(6), orders().get(0).quantity());
    }

    @Test
    void 천원_종목은_51주를_산다() {
        gate().onSignal(buy("VB-DAY", "005930", "1000", 1));
        assertEquals(new Quantity(51), orders().get(0).quantity());
    }

    @Test
    void 분할이_아닌_매수는_예산_수량이_유의미하면_그대로다() {
        // 10만 원 ÷ 1만 원 = 10주(1% 수익 1,000원)
        gate().onSignal(buy("VB-DAY", "005930", "10000", null));
        assertEquals(new Quantity(10), orders().get(0).quantity());
    }

    @Test
    void 일주가_종목_상한보다_비싸면_무의미_거래로_거부한다() {
        gate().onSignal(buy("VB-DAY", "005930", "250000", 1));
        assertTrue(orders().isEmpty());
        assertTrue(rejections().stream().anyMatch(r -> r.startsWith("무의미 거래")), rejections().toString());
    }

    @Test
    void 이차_분할은_보유가_있어야_한다() {
        gate().onSignal(buy("VB-DAY", "005930", "10000", 2));
        assertTrue(orders().isEmpty());
        assertTrue(rejections().contains("분할 추가 매수인데 보유 없음 — 매수 거부"), rejections().toString());
    }

    @Test
    void 일차_분할은_이미_보유한_종목이면_막는다() {
        filled("VB-DAY", "005930", 6, "10000");
        gate().onSignal(buy("VB-DAY", "005930", "10000", 1));
        assertTrue(orders().isEmpty());
        assertTrue(rejections().contains("이 전략이 이미 보유 중 — 추가 매수 차단"), rejections().toString());
    }

    @Test
    void 이차_분할은_보유_종목에_더_사고_동시_보유_한도를_세지_않는다() {
        filled("VB-DAY", "005930", 6, "10000");
        filled("VB-DAY", "000660", 6, "10000");
        filled("VB-DAY", "035420", 6, "10000");   // 전략 동시 보유 3 — 꽉 참
        gate().onSignal(buy("VB-DAY", "005930", "10000", 2));
        assertEquals(1, orders().size(), rejections().toString());
        assertEquals(new Quantity(6), orders().get(0).quantity());   // 보유 원가 6만 + 6만 ≤ 20만
    }

    @Test
    void 종목_누적_원가가_상한을_넘는_분할은_거부한다() {
        filled("VB-DAY", "005930", 15, "10000");   // 15만 원 보유 — 남은 여유 5만 원 < 최소 6주 6만 원
        gate().onSignal(buy("VB-DAY", "005930", "10000", 3));
        assertTrue(orders().isEmpty());
        assertTrue(rejections().stream().anyMatch(r -> r.startsWith("무의미 거래")), rejections().toString());
    }

    @Test
    void 분할_차수가_범위_밖이면_거부한다() {
        gate().onSignal(buy("VB-DAY", "005930", "10000", 4));
        assertTrue(orders().isEmpty());
        assertTrue(rejections().contains("분할 차수 범위 밖 — 매수 거부"), rejections().toString());
    }

    @Test
    void 예산이_작은_전략도_1주_5만원_이하_종목은_유의미한_수량을_산다() {
        // TA-H1 종목 예산 200만 × 4% ÷ 5 = 16,000원 → 1주(1% 160원) → 최소 6주 6만 원 ≤ 상한 10만 원
        gate().onSignal(buy("TA-H1", "035720", "10000", null));
        assertEquals(new Quantity(6), orders().get(0).quantity());
    }
}

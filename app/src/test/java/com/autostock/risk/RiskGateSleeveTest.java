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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 다전략 슬리브(2026-10-16, aiDoc/multi-strategy-paper-2026-10-16.md 1절) — 전략별 예산·보유·매도 수량과
 * 손실 한도 킬스위치 중 매도 예외(D-20 b).
 */
class RiskGateSleeveTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T01:00:00Z"), ZoneOffset.UTC);
    private static final StockCode SAMSUNG = new StockCode("005930");

    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher publisher = published::add;
    private final Set<StockCode> openBuys = new HashSet<>();
    private final Set<StockCode> strategyOpenSells = new HashSet<>();
    private final OpenOrderQuery openOrders = new OpenOrderQuery() {
        @Override
        public Set<StockCode> symbolsWithOpenBuy() {
            return openBuys;
        }

        @Override
        public Set<StockCode> symbolsWithOpenOrder(String strategyId, Side side) {
            return side == Side.SELL ? strategyOpenSells : Set.of();
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

    private RiskGate gate(boolean allowReducingSell) {
        MultiStrategyRiskProperties multi = new MultiStrategyRiskProperties(allowReducingSell, List.of(
                new MultiStrategyRiskProperties.Sleeve("C3-MOMENTUM", 0.50, 5),
                new MultiStrategyRiskProperties.Sleeve("VB-DAY", 0.15, 3),
                new MultiStrategyRiskProperties.Sleeve("TA-H1", 0.04, 5)));
        MacroGuard macroGuard = new MacroGuard(RiskGateTest.defaultMacroIntelProperties(), killSwitch, event -> { },
                Clock.systemUTC());
        DisclosureBlacklist blacklist = new DisclosureBlacklist(mock(DisclosureBlacklistRepository.class), publisher, CLOCK);
        return new RiskGate(publisher, killSwitch, properties, new PositionSizer(properties), positionBook,
                new DailyLimitTracker(properties, CLOCK), new PaperEquitySource(properties), CLOCK,
                new MarketCalendarService(mock(MarketHolidayRepository.class)), macroGuard, blacklist, openOrders,
                strategyBook, multi);
    }

    /** 체결 — 계좌 장부와 전략 장부에 함께 넣는다(운영에서는 둘 다 Fill 이벤트를 듣는다). */
    private void filled(String strategyId, String symbol, Side side, long qty, String price) {
        String id = ClientOrderId.generate(LocalDate.of(2026, 10, 19), strategyId, symbol, side, ++seq).value();
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
    void 슬리브_예산으로_매수_수량을_정한다() {
        // 1억 × 15% ÷ 3 = 500만 원 ÷ 10만 원 = 50주
        gate(false).onSignal(signal("VB-DAY", "005930", Side.BUY, "100000"));
        assertEquals(new Quantity(50), orders().get(0).quantity());
        // 차트 기법: 1억 × 4% ÷ 5 = 80만 원 ÷ 10만 원 = 8주
        gate(false).onSignal(signal("TA-H1", "000660", Side.BUY, "100000"));
        assertEquals(new Quantity(8), orders().get(1).quantity());
    }

    @Test
    void C3_슬리브는_예전_사이징과_같다() {
        // 1억 × 50% ÷ 5 = 1,000만 = 예전 종목당 10%
        gate(false).onSignal(signal("C3-MOMENTUM", "005930", Side.BUY, "100000"));
        assertEquals(new Quantity(100), orders().get(0).quantity());
    }

    @Test
    void 다른_전략이_든_종목도_살_수_있다_슬리브_없는_전략은_예전대로_막힌다() {
        filled("TA-H1", "005930", Side.BUY, 3, "100000");
        RiskGate gate = gate(false);

        gate.onSignal(signal("VB-DAY", "005930", Side.BUY, "100000"));
        assertEquals(1, orders().size());

        gate.onSignal(signal("test-strategy", "005930", Side.BUY, "100000"));
        assertEquals(1, orders().size(), "슬리브 없는 전략은 계좌 보유로 막힌다");
        assertTrue(rejections().contains("이미 보유 중 — 추가 매수 차단"));
    }

    @Test
    void 이_전략이_이미_보유하면_추가_매수를_막는다() {
        filled("VB-DAY", "005930", Side.BUY, 10, "100000");
        gate(false).onSignal(signal("VB-DAY", "005930", Side.BUY, "101000"));
        assertTrue(orders().isEmpty());
        assertTrue(rejections().contains("이 전략이 이미 보유 중 — 추가 매수 차단"));
    }

    @Test
    void 전략_동시_보유_한도를_전략마다_센다() {
        filled("VB-DAY", "005930", Side.BUY, 1, "100000");
        filled("VB-DAY", "000660", Side.BUY, 1, "100000");
        filled("VB-DAY", "035420", Side.BUY, 1, "100000");
        RiskGate gate = gate(false);

        gate.onSignal(signal("VB-DAY", "035720", Side.BUY, "50000"));
        assertTrue(orders().isEmpty());
        assertTrue(rejections().contains("전략 동시 보유 한도 도달(3) — 매수 거부"));

        gate.onSignal(signal("TA-H1", "035720", Side.BUY, "50000"));
        assertEquals(1, orders().size(), "다른 전략은 자기 한도로 본다");
    }

    @Test
    void 매도는_그_전략이_든_수량만_판다() {
        filled("VB-DAY", "005930", Side.BUY, 10, "100000");
        filled("TA-H1", "005930", Side.BUY, 3, "100000");

        gate(false).onSignal(signal("VB-DAY", "005930", Side.SELL, "101000"));

        assertEquals(new Quantity(10), orders().get(0).quantity());
        assertEquals("VB-DAY", orders().get(0).strategyId());
    }

    @Test
    void 이_전략이_보유하지_않으면_매도_신호를_무시한다() {
        filled("TA-H1", "005930", Side.BUY, 3, "100000");
        gate(false).onSignal(signal("VB-DAY", "005930", Side.SELL, "101000"));
        assertTrue(orders().isEmpty());
        assertTrue(rejections().contains("이 전략은 보유하지 않음 — 매도 시그널 무시"));
    }

    @Test
    void 이_전략의_미체결_매도가_있으면_중복_매도를_막는다() {
        filled("VB-DAY", "005930", Side.BUY, 10, "100000");
        strategyOpenSells.add(SAMSUNG);
        gate(false).onSignal(signal("VB-DAY", "005930", Side.SELL, "101000"));
        assertTrue(orders().isEmpty());
        assertTrue(rejections().contains("이 전략의 미체결 매도 있음 — 중복 매도 차단"));
    }

    @Test
    void 손실_한도_킬스위치라도_설정이_켜져_있으면_보유_이내_매도는_통과한다() {
        filled("VB-DAY", "005930", Side.BUY, 10, "100000");
        killSwitch.engage(DailyPnlTracker.LOSS_LIMIT_REASON_PREFIX + ": 오늘 손익 -5,100,000원");
        RiskGate gate = gate(true);

        gate.onSignal(signal("VB-DAY", "005930", Side.SELL, "95000"));
        assertEquals(new Quantity(10), orders().get(0).quantity());

        gate.onSignal(signal("VB-DAY", "000660", Side.BUY, "100000"));
        assertEquals(1, orders().size(), "매수는 여전히 막힌다");
    }

    @Test
    void 재기동_복원된_손실_한도_사유도_알아본다() {
        filled("ON-CLOSE", "005930", Side.BUY, 5, "100000");
        killSwitch.engage("재기동 복원: " + DailyPnlTracker.LOSS_LIMIT_REASON_PREFIX + ": …");
        gate(true).onSignal(signal("C3-MOMENTUM", "005930", Side.SELL, "95000"));
        assertTrue(orders().isEmpty(), "C3는 이 종목을 들고 있지 않다 — 슬리브 매도 수량 0");
        assertTrue(rejections().contains("이 전략은 보유하지 않음 — 매도 시그널 무시"));
    }

    @Test
    void 다른_사유의_킬스위치나_설정이_꺼져_있으면_매도도_막는다() {
        filled("VB-DAY", "005930", Side.BUY, 10, "100000");
        killSwitch.engage("WS 단절 180초");
        gate(true).onSignal(signal("VB-DAY", "005930", Side.SELL, "95000"));
        assertTrue(orders().isEmpty());

        killSwitch.release("test");
        killSwitch.engage(DailyPnlTracker.LOSS_LIMIT_REASON_PREFIX + ": …");
        gate(false).onSignal(signal("VB-DAY", "005930", Side.SELL, "95000"));
        assertTrue(orders().isEmpty(), "설정이 꺼져 있으면 예전처럼 전부 막는다");
    }

    @Test
    void 지평은_전략_ID에서_유도한다() {
        assertEquals("MID", RiskGate.horizonFor("C3-MOMENTUM"));
        assertEquals("DAY", RiskGate.horizonFor("VB-DAY"));
        assertEquals("DAY", RiskGate.horizonFor("ON-CLOSE"));
        assertEquals("SWING", RiskGate.horizonFor("TA-H3"));
        assertEquals("TEST", RiskGate.horizonFor("dashboard-manual"));
    }

    @Test
    void 슬리브_합이_100퍼센트를_넘으면_기동하지_않는다() {
        assertThrows(IllegalArgumentException.class, () -> new MultiStrategyRiskProperties(false, List.of(
                new MultiStrategyRiskProperties.Sleeve("A", 0.6, 1), new MultiStrategyRiskProperties.Sleeve("B", 0.5, 1))));
        assertThrows(IllegalArgumentException.class, () -> new MultiStrategyRiskProperties.Sleeve("A", 0.1, 0));
        assertTrue(new MultiStrategyRiskProperties(false, List.of(new MultiStrategyRiskProperties.Sleeve("VB-DAY", 0.1, 1)))
                .sleeveOf("vb-day").isPresent(), "대소문자 무시");
    }
}

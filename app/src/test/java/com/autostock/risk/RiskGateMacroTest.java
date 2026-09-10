package com.autostock.risk;

import com.autostock.common.event.Fill;
import com.autostock.common.event.MacroIndicator;
import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Side;
import com.autostock.common.event.Signal;
import com.autostock.macrointel.MacroIntelProperties;
import com.autostock.market.MarketCalendarService;
import com.autostock.market.MarketHolidayRepository;
import com.autostock.portfolio.PositionBook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

// FE-6(SignalDecision)부터는 거부 시 REJECTED SignalDecision도 함께 발행되므로, 아래
// isEmpty() 단언들은 OrderRequest만 걸러서 검증한다(RiskGateTest.onlyOrders와 동일 취지).

/**
 * RiskGate ↔ MacroGuard/DisclosureBlacklist 연동 검증 — 보수 모드에서 매수 거부·매도 허용,
 * 블랙리스트 종목 매수 거부(RiskGate 클래스 설명 "거시 국면·공시 배제" 절 참고).
 */
class RiskGateMacroTest {

    private static final Clock ANY_CLOCK = Clock.fixed(Instant.parse("2026-08-13T02:00:00Z"), ZoneOffset.UTC);

    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher publisher = published::add;

    private RiskProperties properties;
    private KillSwitch killSwitch;
    private PositionBook positionBook;
    private MacroGuard macroGuard;
    private DisclosureBlacklist disclosureBlacklist;
    private RiskGate gate;

    @BeforeEach
    void setUp() {
        properties = new RiskProperties(0.10, 5, -0.03, 0.05, -0.02, 30,
                10_000_000, 0.00015, 0.0015, false);
        killSwitch = new KillSwitch(event -> { });
        positionBook = new PositionBook();
        macroGuard = new MacroGuard(
                new MacroIntelProperties(false, "", "", 25.0, 35.0, 1450.0), killSwitch);
        disclosureBlacklist = new DisclosureBlacklist(
                mock(DisclosureBlacklistRepository.class), publisher, ANY_CLOCK);
        MarketCalendarService marketCalendarService = new MarketCalendarService(mock(MarketHolidayRepository.class));
        gate = new RiskGate(publisher, killSwitch, properties,
                new PositionSizer(properties), positionBook, new DailyLimitTracker(properties),
                new PaperEquitySource(properties), ANY_CLOCK, marketCalendarService,
                macroGuard, disclosureBlacklist);
    }

    private Signal buySignal(String symbol, String price) {
        return new Signal("test-strategy", symbol, Side.BUY, new BigDecimal(price), 1.0, Instant.now());
    }

    private Signal sellSignal(String symbol, String price) {
        return new Signal("test-strategy", symbol, Side.SELL, new BigDecimal(price), 1.0, Instant.now());
    }

    private static List<OrderRequest> onlyOrders(List<Object> published) {
        return published.stream().filter(OrderRequest.class::isInstance).map(OrderRequest.class::cast).toList();
    }

    private void engageConservativeMode() {
        // VIX 30 — severe(35) 미만이라 킬스위치는 켜지지 않고 보수 모드만 켜진다.
        macroGuard.onMacroIndicator(new MacroIndicator("FRED_VIX", "MARKET", new BigDecimal("30.0"), null, Instant.now()));
        assertTrue(macroGuard.isConservativeMode(), "사전조건: 보수 모드가 켜져 있어야 함");
    }

    @Test
    void 보수모드에서_신규_매수는_거부된다() {
        engageConservativeMode();
        gate.onSignal(buySignal("005930", "70000"));
        assertTrue(onlyOrders(published).isEmpty());
    }

    @Test
    void 보수모드에서도_보유_종목_매도_청산은_허용된다() {
        positionBook.onFill(new Fill("k1", "b1", "005930", Side.BUY, 14,
                new BigDecimal("70000"), Instant.now()));
        engageConservativeMode();

        gate.onSignal(sellSignal("005930", "71000"));

        assertEquals(1, published.size());
        OrderRequest order = (OrderRequest) published.get(0);
        assertEquals(Side.SELL, order.side());
        assertEquals(14, order.quantity());
    }

    @Test
    void 블랙리스트_종목은_매수가_거부된다() {
        disclosureBlacklist.add("005930");
        gate.onSignal(buySignal("005930", "70000"));
        assertTrue(onlyOrders(published).isEmpty());
    }

    @Test
    void 블랙리스트에서_제거하면_다시_매수할_수_있다() {
        disclosureBlacklist.add("005930");
        disclosureBlacklist.remove("005930");
        gate.onSignal(buySignal("005930", "70000"));
        assertEquals(1, published.size());
    }

    @Test
    void 블랙리스트_종목도_보유중이면_매도는_허용된다() {
        positionBook.onFill(new Fill("k1", "b1", "005930", Side.BUY, 14,
                new BigDecimal("70000"), Instant.now()));
        disclosureBlacklist.add("005930");

        gate.onSignal(sellSignal("005930", "71000"));

        assertEquals(1, published.size());
        assertEquals(Side.SELL, ((OrderRequest) published.get(0)).side());
    }
}

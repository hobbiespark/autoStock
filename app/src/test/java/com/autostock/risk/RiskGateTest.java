package com.autostock.risk;

import com.autostock.common.event.Fill;
import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Side;
import com.autostock.common.event.Signal;
import com.autostock.common.event.SignalDecision;
import com.autostock.common.util.StockCode;
import com.autostock.macrointel.MacroIntelProperties;
import com.autostock.market.MarketCalendarService;
import com.autostock.market.MarketHolidayRepository;
import com.autostock.portfolio.PositionBook;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RiskGateTest {

    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher publisher = published::add;

    // 임의의 시각(UTC) — enforceMarketHours=false인 테스트에서는 실제로 쓰이지 않는다.
    // 장 시간 가드 자체를 검증하는 테스트는 별도로 원하는 시각을 직접 고정해 만든다.
    private static final Clock ANY_CLOCK = Clock.fixed(Instant.parse("2026-08-13T02:00:00Z"), ZoneOffset.UTC);

    private RiskProperties properties;
    private KillSwitch killSwitch;
    private PositionBook positionBook;
    // isMarketHours()는 DB를 전혀 보지 않고 TradingCalendar에 순수 위임하므로, repository는
    // 실제로 호출되지 않는다 — mock으로 충분하다(RiskGate 클래스 설명 "market 모듈 참조" 절 참고).
    private final MarketCalendarService marketCalendarService = new MarketCalendarService(mock(MarketHolidayRepository.class));
    // 이 스위트는 사이징/한도/킬스위치가 관심 대상이라 macro-intel 관련 기본값(보수 모드
    // OFF, 블랙리스트 비어있음)으로 둔다 — 두 장치 자체는 MacroGuardTest·RiskGateMacroTest에서
    // 별도 검증한다.
    private MacroGuard macroGuard;
    private DisclosureBlacklist disclosureBlacklist;
    private DailyLimitTracker dailyLimits;
    /** 미체결 매수 종목(실행 계획 1.2) — 테스트가 직접 채운다. */
    private final Set<StockCode> openBuys = new HashSet<>();
    private final OpenOrderQuery openOrderQuery = () -> openBuys;
    private RiskGate gate;

    @BeforeEach
    void setUp() {
        // enforceMarketHours=false — 이 테스트 스위트는 사이징/한도/킬스위치만 관심 대상이라
        // 실제 실행 시각과 무관하게 결정론적으로 돌게 장 시간 가드를 꺼둔다(RiskGate 클래스
        // 설명의 "장 시간 가드 설계" 절 참고). 시간 가드 자체는 아래 별도 테스트에서 검증한다.
        properties = new RiskProperties(0.10, 5, -0.03, 0.05, -0.02, 30,
                10_000_000, 0.00015, 0.0015, false, 1_000_000);
        // killSwitch 전용 publisher는 이 테스트의 published 리스트와 분리한다 —
        // KillSwitchChanged 이벤트가 여기 섞이면 "주문이 published에 없다"를 검증하는
        // 기존 단언들이 killSwitch.engage() 한 번에 깨진다(이 테스트는 OrderRequest만 관심 대상).
        killSwitch = new KillSwitch(event -> { }, mock(RiskStateStore.class), Clock.systemUTC());
        positionBook = new PositionBook();
        macroGuard = new MacroGuard(defaultMacroIntelProperties(), killSwitch);
        disclosureBlacklist = new DisclosureBlacklist(
                mock(DisclosureBlacklistRepository.class), publisher, ANY_CLOCK);
        dailyLimits = new DailyLimitTracker(properties, ANY_CLOCK);
        gate = new RiskGate(publisher, killSwitch, properties,
                new PositionSizer(properties), positionBook, dailyLimits,
                new PaperEquitySource(properties), ANY_CLOCK, marketCalendarService,
                macroGuard, disclosureBlacklist, openOrderQuery);
    }

    /** macro-intel 관련 테스트는 이 기본 임계치를 공유한다(PLAN 5절 기본값과 동일). */
    static MacroIntelProperties defaultMacroIntelProperties() {
        return new MacroIntelProperties(false, "", "", 25.0, 35.0, 1450.0);
    }

    private Signal buySignal(String symbol, String price) {
        return new Signal("test-strategy", new StockCode(symbol), Side.BUY, new Price(new BigDecimal(price)), 1.0, Instant.now());
    }

    /**
     * FE-6(SignalDecision)부터는 거부 지점마다 RiskGate가 REJECTED SignalDecision도 함께
     * 발행한다 — 기존 "published.isEmpty()로 거부를 확인"하던 단언들은 OrderRequest만
     * 걸러서 그 의도를 유지한다.
     */
    private static List<OrderRequest> onlyOrders(List<Object> published) {
        return published.stream().filter(OrderRequest.class::isInstance).map(OrderRequest.class::cast).toList();
    }

    @Test
    void 고정비율_사이징으로_매수_주문_발행() {
        gate.onSignal(buySignal("005930", "70000"));

        assertEquals(1, published.size());
        OrderRequest order = (OrderRequest) published.get(0);
        // 1,000만원 * 10% = 100만원 / 7만원 = 14주
        assertEquals(new Quantity(14), order.quantity());
        assertEquals(Side.BUY, order.side());
    }

    @Test
    void 킬스위치_작동시_주문_차단() {
        killSwitch.engage("테스트");
        gate.onSignal(buySignal("005930", "70000"));
        assertTrue(onlyOrders(published).isEmpty());

        // FE-6 — REJECTED SignalDecision이 로그와 같은 문구로 남는지 확인.
        SignalDecision decision = (SignalDecision) published.get(published.size() - 1);
        assertEquals("REJECTED", decision.conclusion());
        assertEquals("킬스위치 작동 중 — 시그널 거부", decision.reason());
        assertEquals("TEST", decision.horizon()); // strategyId="test-strategy" → C3 아님 → TEST
    }

    @Test
    void 보유_종목_추가_매수_차단() {
        positionBook.onFill(new Fill("k1", new BrokerOrderId("b1"), new StockCode("005930"), Side.BUY, new Quantity(10),
                new Price(new BigDecimal("70000")), Instant.now()));
        gate.onSignal(buySignal("005930", "70000"));
        assertTrue(onlyOrders(published).isEmpty());
    }

    @Test
    void 미보유_종목_매도_시그널_무시() {
        gate.onSignal(new Signal("test-strategy", new StockCode("005930"), Side.SELL,
                new Price(new BigDecimal("70000")), 1.0, Instant.now()));
        assertTrue(onlyOrders(published).isEmpty());
    }

    @Test
    void 보유_종목_매도는_전량_청산() {
        positionBook.onFill(new Fill("k1", new BrokerOrderId("b1"), new StockCode("005930"), Side.BUY, new Quantity(14),
                new Price(new BigDecimal("70000")), Instant.now()));
        gate.onSignal(new Signal("test-strategy", new StockCode("005930"), Side.SELL,
                new Price(new BigDecimal("71000")), 1.0, Instant.now()));

        assertEquals(1, published.size());
        assertEquals(new Quantity(14), ((OrderRequest) published.get(0)).quantity());
    }

    // ── 지정가(조각 16, 2026-09-30): OrderRequest.limitPrice는 Price — 0·null이면 주문을 만들지 않는다 ──

    @Test
    void 기준가_없는_매도는_거부하고_주문_슬롯을_쓰지_않는다() {
        // 기준가 없음(null)은 평단 미상 포지션·호가 조회 실패에서 온다(조각 19·20). 0은 Signal 생산자가 null로 번역한다(조각 21).
        positionBook.onFill(new Fill("k1", new BrokerOrderId("b1"), new StockCode("005930"), Side.BUY, new Quantity(14),
                new Price(new BigDecimal("70000")), Instant.now()));

        gate.onSignal(new Signal("test-strategy", new StockCode("005930"), Side.SELL, null, 1.0, Instant.now()));

        assertTrue(onlyOrders(published).isEmpty());
        SignalDecision decision = (SignalDecision) published.get(published.size() - 1);
        assertEquals("REJECTED", decision.conclusion());
        assertEquals("기준가 없음 — 지정가를 정할 수 없어 거부", decision.reason());

        gate.onSignal(new Signal("test-strategy", new StockCode("005930"), Side.SELL,
                new Price(new BigDecimal("71000")), 1.0, Instant.now()));
        OrderRequest order = onlyOrders(published).get(0);
        assertTrue(order.idempotencyKey().endsWith("-001"), "거부된 신호는 일 주문 슬롯을 쓰지 않는다: " + order.idempotencyKey());
    }

    @Test
    void 지정가는_호가단위로_보정한_Price로_실린다() {
        gate.onSignal(buySignal("005930", "259300"));   // 20만~50만원 구간 눈금 500원 → 259,500

        assertEquals(new Price(new BigDecimal("259500")), onlyOrders(published).get(0).limitPrice());
    }

    @Test
    void confidence가_0_5면_매수_수량이_절반이다() {
        gate.onSignal(new Signal("test-strategy", new StockCode("005930"), Side.BUY, new Price(new BigDecimal("70000")), 0.5, Instant.now()));

        assertEquals(1, published.size());
        OrderRequest order = (OrderRequest) published.get(0);
        // 1,000만원 * 10% * 0.5 = 50만원 / 7만원 = 7.14... → 7주 (confidence=1.0일 때 14주의 절반)
        assertEquals(new Quantity(7), order.quantity());
    }

    @Test
    void confidence가_1_초과면_1_0으로_클램프돼_원래_수량과_같다() {
        gate.onSignal(new Signal("test-strategy", new StockCode("005930"), Side.BUY, new Price(new BigDecimal("70000")), 1.5, Instant.now()));

        assertEquals(1, published.size());
        assertEquals(new Quantity(14), ((OrderRequest) published.get(0)).quantity());
    }

    @Test
    void confidence가_0이하면_1_0으로_클램프돼_원래_수량과_같다() {
        gate.onSignal(new Signal("test-strategy", new StockCode("005930"), Side.BUY, new Price(new BigDecimal("70000")), 0.0, Instant.now()));

        assertEquals(1, published.size());
        assertEquals(new Quantity(14), ((OrderRequest) published.get(0)).quantity());
    }

    @Test
    void 동시_보유_한도_도달시_신규_매수_차단() {
        String[] symbols = {"000001", "000002", "000003", "000004", "000005"};
        for (String s : symbols) {
            positionBook.onFill(new Fill("k" + s, new BrokerOrderId("b" + s), new StockCode(s), Side.BUY, new Quantity(1),
                    new Price(new BigDecimal("1000")), Instant.now()));
        }
        gate.onSignal(buySignal("005930", "70000"));
        assertTrue(onlyOrders(published).isEmpty());
    }

    // ── 장 시간 가드 전용 테스트 — enforceMarketHours=true로 별도 게이트를 구성한다 ──────────

    /** 2026-08-13(목) 10:00 KST = 01:00 UTC — 정규장(09:00~15:30 KST) 이내. */
    private static final Clock DURING_MARKET_HOURS =
            Clock.fixed(Instant.parse("2026-08-13T01:00:00Z"), ZoneOffset.UTC);

    /** 2026-08-13(목) 20:00 KST = 11:00 UTC — 정규장 종료(15:30) 이후. */
    private static final Clock AFTER_MARKET_HOURS =
            Clock.fixed(Instant.parse("2026-08-13T11:00:00Z"), ZoneOffset.UTC);

    private RiskGate gateWithMarketHoursGuard(Clock clock) {
        RiskProperties guardedProperties = new RiskProperties(0.10, 5, -0.03, 0.05, -0.02, 30,
                10_000_000, 0.00015, 0.0015, true, 1_000_000);
        return new RiskGate(publisher, killSwitch, guardedProperties,
                new PositionSizer(guardedProperties), positionBook, new DailyLimitTracker(guardedProperties, clock),
                new PaperEquitySource(guardedProperties), clock, marketCalendarService,
                macroGuard, disclosureBlacklist, openOrderQuery);
    }

    @Test
    void 장시간_가드_켠_상태에서_장중이면_주문이_발행된다() {
        RiskGate guarded = gateWithMarketHoursGuard(DURING_MARKET_HOURS);
        guarded.onSignal(buySignal("005930", "70000"));
        assertEquals(1, published.size());
    }

    @Test
    void 장시간_가드_켠_상태에서_장외이면_시그널이_거부된다() {
        RiskGate guarded = gateWithMarketHoursGuard(AFTER_MARKET_HOURS);
        guarded.onSignal(buySignal("005930", "70000"));
        assertTrue(onlyOrders(published).isEmpty());
    }

    // ---- 수동 주문(대시보드 테스트 시그널) — Phase 0.4 금액 상한(D-06, aiDoc/manual-order-guard.md) ----

    private static Signal manualSignal(Side side, String price, long quantity) {
        return new Signal("dashboard-manual", new StockCode("005930"), side, new Price(new BigDecimal(price)), 1.0,
                quantity, Instant.now());
    }

    private void hold(long quantity, String avgPrice) {
        positionBook.onFill(new Fill("seed", new BrokerOrderId("seed"), new StockCode("005930"), Side.BUY,
                new Quantity(quantity), new Price(new BigDecimal(avgPrice)), Instant.now()));
    }

    @Test
    void 수동_매수_금액이_상한을_넘으면_거부되고_주문_슬롯을_쓰지_않는다() {
        // 5주 × 260,000 = 1,300,000원 > 상한 1,000,000원
        gate.onSignal(manualSignal(Side.BUY, "260000", 5));

        assertTrue(onlyOrders(published).isEmpty());
        SignalDecision decision = (SignalDecision) published.get(published.size() - 1);
        assertEquals("수동 주문 금액 상한 초과 — 거부", decision.reason());
        assertEquals("1300000", decision.metrics().get("amount"));
        assertEquals(0, dailyLimits.todayOrderCount());
    }

    @Test
    void 수동_매수는_상한과_같은_금액까지_지정_수량으로_발행된다() {
        // 4주 × 250,000 = 1,000,000원 = 상한(초과만 거부)
        gate.onSignal(manualSignal(Side.BUY, "250000", 4));

        List<OrderRequest> orders = onlyOrders(published);
        assertEquals(1, orders.size());
        assertEquals(new Quantity(4), orders.get(0).quantity());
    }

    @Test
    void 수동_매도에는_금액_상한을_적용하지_않는다() {
        // 보유분 수동 청산을 막지 않는다 — 19주 × 260,000 = 4,940,000원
        hold(19, "259974");

        gate.onSignal(manualSignal(Side.SELL, "260000", 19));

        List<OrderRequest> orders = onlyOrders(published);
        assertEquals(1, orders.size());
        assertEquals(new Quantity(19), orders.get(0).quantity());
    }

    @Test
    void 수동_매도_수량은_보유량으로_캡된다() {
        hold(10, "70000");

        gate.onSignal(manualSignal(Side.SELL, "70000", 23));

        assertEquals(new Quantity(10), onlyOrders(published).get(0).quantity());
    }

    // ── 미체결 매수 인지(실행 계획 1.2, BE-P1-2) — 장부는 체결된 것만 안다 ──────────────────

    private void holdSymbol(String symbol) {
        positionBook.onFill(new Fill("k" + symbol, new BrokerOrderId("b" + symbol), new StockCode(symbol), Side.BUY,
                new Quantity(1), new Price(new BigDecimal("1000")), Instant.now()));
    }

    private SignalDecision lastDecision() {
        return (SignalDecision) published.get(published.size() - 1);
    }

    @Test
    void 같은_종목에_미체결_매수가_있으면_매수를_거부하고_주문_슬롯을_쓰지_않는다() {
        openBuys.add(new StockCode("005930"));

        gate.onSignal(buySignal("005930", "70000"));

        assertTrue(onlyOrders(published).isEmpty());
        assertEquals("미체결 매수 주문 있음 — 추가 매수 차단", lastDecision().reason());
        assertEquals(0, dailyLimits.todayOrderCount());
    }

    @Test
    void 다른_종목의_미체결_매수는_매수를_막지_않는다() {
        openBuys.add(new StockCode("000660"));

        gate.onSignal(buySignal("005930", "70000"));

        assertEquals(1, onlyOrders(published).size());
    }

    @Test
    void 동시_보유_한도는_미체결_매수_종목도_센다() {
        // 보유 4 + 미체결 매수 1(미보유 종목) = 5 = 한도 → 새 종목 매수 거부
        for (String s : new String[]{"000001", "000002", "000003", "000004"}) {
            holdSymbol(s);
        }
        openBuys.add(new StockCode("000005"));

        gate.onSignal(buySignal("005930", "70000"));

        assertTrue(onlyOrders(published).isEmpty());
        SignalDecision decision = lastDecision();
        assertEquals("동시 보유 한도 도달(5) — 매수 거부", decision.reason());
        assertEquals("4", decision.metrics().get("openPositionCount"));
        assertEquals("1", decision.metrics().get("pendingBuyCount"));
    }

    @Test
    void 이미_보유한_종목의_미체결_매수는_한도에서_두_번_세지_않는다() {
        for (String s : new String[]{"000001", "000002", "000003", "000004"}) {
            holdSymbol(s);
        }
        openBuys.add(new StockCode("000001")); // 부분 체결 등 — 이미 장부에 있다

        gate.onSignal(buySignal("005930", "70000"));

        assertEquals(1, onlyOrders(published).size());
    }

    @Test
    void 미체결_주문_조회가_실패하면_매수는_거부하고_매도는_그대로_낸다() {
        RiskGate failing = new RiskGate(publisher, killSwitch, properties,
                new PositionSizer(properties), positionBook, dailyLimits,
                new PaperEquitySource(properties), ANY_CLOCK, marketCalendarService,
                macroGuard, disclosureBlacklist, () -> {
                    throw new IllegalStateException("DB 연결 끊김");
                });
        hold(10, "70000");

        failing.onSignal(buySignal("000660", "200000"));
        assertTrue(onlyOrders(published).isEmpty());
        assertEquals("미체결 주문 조회 실패 — 매수 거부", lastDecision().reason());

        failing.onSignal(new Signal("test-strategy", new StockCode("005930"), Side.SELL,
                new Price(new BigDecimal("71000")), 1.0, Instant.now()));
        assertEquals(1, onlyOrders(published).size());
        assertEquals(Side.SELL, onlyOrders(published).get(0).side());
    }

    @Test
    void 수동_매수는_미체결_매수_검사를_받지_않는다() {
        // 수동 지정은 물타기 금지·동시 보유 한도를 적용하지 않는다(운영자 의도) — 미체결 매수도 같은 성격
        openBuys.add(new StockCode("005930"));

        gate.onSignal(manualSignal(Side.BUY, "70000", 1));

        assertEquals(1, onlyOrders(published).size());
    }

    @Test
    void 주문_ID는_슬롯이_준_날짜와_일련번호로_만든다() {
        gate.onSignal(buySignal("005930", "70000"));
        gate.onSignal(buySignal("000660", "200000"));

        List<OrderRequest> orders = onlyOrders(published);
        // ANY_CLOCK = 2026-08-13T02:00Z = KST 08-13 11:00
        assertEquals("20260813-TEST-STRATEGY-005930-BUY-001", orders.get(0).idempotencyKey());
        assertEquals("20260813-TEST-STRATEGY-000660-BUY-002", orders.get(1).idempotencyKey());
    }
}

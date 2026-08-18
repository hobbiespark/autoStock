package com.autostock.risk;

import com.autostock.common.event.Fill;
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
    private RiskGate gate;

    @BeforeEach
    void setUp() {
        // enforceMarketHours=false — 이 테스트 스위트는 사이징/한도/킬스위치만 관심 대상이라
        // 실제 실행 시각과 무관하게 결정론적으로 돌게 장 시간 가드를 꺼둔다(RiskGate 클래스
        // 설명의 "장 시간 가드 설계" 절 참고). 시간 가드 자체는 아래 별도 테스트에서 검증한다.
        properties = new RiskProperties(0.10, 5, -0.03, 0.05, -0.02, 30,
                10_000_000, 0.00015, 0.0015, false);
        // killSwitch 전용 publisher는 이 테스트의 published 리스트와 분리한다 —
        // KillSwitchChanged 이벤트가 여기 섞이면 "주문이 published에 없다"를 검증하는
        // 기존 단언들이 killSwitch.engage() 한 번에 깨진다(이 테스트는 OrderRequest만 관심 대상).
        killSwitch = new KillSwitch(event -> { });
        positionBook = new PositionBook();
        macroGuard = new MacroGuard(defaultMacroIntelProperties(), killSwitch);
        disclosureBlacklist = new DisclosureBlacklist();
        gate = new RiskGate(publisher, killSwitch, properties,
                new PositionSizer(properties), positionBook, new DailyLimitTracker(properties),
                new PaperEquitySource(properties), ANY_CLOCK, marketCalendarService,
                macroGuard, disclosureBlacklist);
    }

    /** macro-intel 관련 테스트는 이 기본 임계치를 공유한다(PLAN 5절 기본값과 동일). */
    static MacroIntelProperties defaultMacroIntelProperties() {
        return new MacroIntelProperties(false, "", "", 25.0, 35.0, 1450.0);
    }

    private Signal buySignal(String symbol, String price) {
        return new Signal("test-strategy", symbol, Side.BUY, new BigDecimal(price), 1.0, Instant.now());
    }

    @Test
    void 고정비율_사이징으로_매수_주문_발행() {
        gate.onSignal(buySignal("005930", "70000"));

        assertEquals(1, published.size());
        OrderRequest order = (OrderRequest) published.get(0);
        // 1,000만원 * 10% = 100만원 / 7만원 = 14주
        assertEquals(14, order.quantity());
        assertEquals(Side.BUY, order.side());
    }

    @Test
    void 킬스위치_작동시_주문_차단() {
        killSwitch.engage("테스트");
        gate.onSignal(buySignal("005930", "70000"));
        assertTrue(published.isEmpty());
    }

    @Test
    void 보유_종목_추가_매수_차단() {
        positionBook.onFill(new Fill("k1", "b1", "005930", Side.BUY, 10,
                new BigDecimal("70000"), Instant.now()));
        gate.onSignal(buySignal("005930", "70000"));
        assertTrue(published.isEmpty());
    }

    @Test
    void 미보유_종목_매도_시그널_무시() {
        gate.onSignal(new Signal("test-strategy", "005930", Side.SELL,
                new BigDecimal("70000"), 1.0, Instant.now()));
        assertTrue(published.isEmpty());
    }

    @Test
    void 보유_종목_매도는_전량_청산() {
        positionBook.onFill(new Fill("k1", "b1", "005930", Side.BUY, 14,
                new BigDecimal("70000"), Instant.now()));
        gate.onSignal(new Signal("test-strategy", "005930", Side.SELL,
                new BigDecimal("71000"), 1.0, Instant.now()));

        assertEquals(1, published.size());
        assertEquals(14, ((OrderRequest) published.get(0)).quantity());
    }

    @Test
    void confidence가_0_5면_매수_수량이_절반이다() {
        gate.onSignal(new Signal("test-strategy", "005930", Side.BUY, new BigDecimal("70000"), 0.5, Instant.now()));

        assertEquals(1, published.size());
        OrderRequest order = (OrderRequest) published.get(0);
        // 1,000만원 * 10% * 0.5 = 50만원 / 7만원 = 7.14... → 7주 (confidence=1.0일 때 14주의 절반)
        assertEquals(7, order.quantity());
    }

    @Test
    void confidence가_1_초과면_1_0으로_클램프돼_원래_수량과_같다() {
        gate.onSignal(new Signal("test-strategy", "005930", Side.BUY, new BigDecimal("70000"), 1.5, Instant.now()));

        assertEquals(1, published.size());
        assertEquals(14, ((OrderRequest) published.get(0)).quantity());
    }

    @Test
    void confidence가_0이하면_1_0으로_클램프돼_원래_수량과_같다() {
        gate.onSignal(new Signal("test-strategy", "005930", Side.BUY, new BigDecimal("70000"), 0.0, Instant.now()));

        assertEquals(1, published.size());
        assertEquals(14, ((OrderRequest) published.get(0)).quantity());
    }

    @Test
    void 동시_보유_한도_도달시_신규_매수_차단() {
        String[] symbols = {"A", "B", "C", "D", "E"};
        for (String s : symbols) {
            positionBook.onFill(new Fill("k" + s, "b" + s, s, Side.BUY, 1,
                    new BigDecimal("1000"), Instant.now()));
        }
        gate.onSignal(buySignal("005930", "70000"));
        assertTrue(published.isEmpty());
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
                10_000_000, 0.00015, 0.0015, true);
        return new RiskGate(publisher, killSwitch, guardedProperties,
                new PositionSizer(guardedProperties), positionBook, new DailyLimitTracker(guardedProperties),
                new PaperEquitySource(guardedProperties), clock, marketCalendarService,
                macroGuard, disclosureBlacklist);
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
        assertTrue(published.isEmpty());
    }
}

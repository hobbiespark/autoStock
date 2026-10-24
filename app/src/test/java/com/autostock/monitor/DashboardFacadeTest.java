package com.autostock.monitor;

import com.autostock.common.event.Fill;
import com.autostock.common.event.Side;
import com.autostock.common.util.StockCode;
import com.autostock.common.util.StockNames;
import com.autostock.execution.BrokerHolding;
import com.autostock.monitor.view.AccountView;
import com.autostock.monitor.view.DashboardView;
import com.autostock.monitor.view.PositionView;
import com.autostock.monitor.view.SystemStatusView;
import com.autostock.monitor.view.TradingStatusView;
import com.autostock.portfolio.PositionBook;
import com.autostock.risk.DailyLimitTracker;
import com.autostock.risk.DailyPnlTracker;
import com.autostock.risk.DisclosureBlacklist;
import com.autostock.risk.KillSwitch;
import com.autostock.risk.MacroGuard;
import com.autostock.trading.TradingProperties;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DashboardFacade 검증 — "업무 규칙 없이 조합만" 한다는 계약을 확인한다.
 * 실제 계산(손익 등)은 risk 모듈이 이미 한 값을 그대로 옮겨 담는지만 본다.
 */
class DashboardFacadeTest {

    private DashboardFacade facade(PositionBook positionBook, EventFeed eventFeed, KillSwitch killSwitch,
                                   DailyLimitTracker dailyLimitTracker, DailyPnlTracker dailyPnlTracker,
                                   TradingSystemManager tradingSystemManager, TradingProperties executionProperties,
                                   boolean c3Enabled, boolean wsEnabled) {
        return facade(positionBook, eventFeed, killSwitch, dailyLimitTracker, dailyPnlTracker,
                mock(MacroGuard.class), tradingSystemManager, executionProperties, c3Enabled, wsEnabled);
    }

    private DashboardFacade facade(PositionBook positionBook, EventFeed eventFeed, KillSwitch killSwitch,
                                   DailyLimitTracker dailyLimitTracker, DailyPnlTracker dailyPnlTracker,
                                   MacroGuard macroGuard,
                                   TradingSystemManager tradingSystemManager, TradingProperties executionProperties,
                                   boolean c3Enabled, boolean wsEnabled) {
        return facade(positionBook, eventFeed, killSwitch, dailyLimitTracker, dailyPnlTracker, macroGuard,
                tradingSystemManager, executionProperties, c3Enabled, wsEnabled, mock(AccountSummaryService.class));
    }

    private DashboardFacade facade(PositionBook positionBook, EventFeed eventFeed, KillSwitch killSwitch,
                                   DailyLimitTracker dailyLimitTracker, DailyPnlTracker dailyPnlTracker,
                                   MacroGuard macroGuard,
                                   TradingSystemManager tradingSystemManager, TradingProperties executionProperties,
                                   boolean c3Enabled, boolean wsEnabled, AccountSummaryService accountSummary) {
        return new DashboardFacade(positionBook, eventFeed, killSwitch, dailyLimitTracker, dailyPnlTracker,
                macroGuard, mock(DisclosureBlacklist.class), tradingSystemManager, executionProperties,
                new SlippageTracker(java.time.Clock.systemUTC(), new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                c3Enabled, wsEnabled, 1_000_000, accountSummary, "https://mockapi.kiwoom.com");
    }

    @Test
    void positions는_PositionBook_스냅샷을_View로_옮겨담는다() {
        StockNames.learn("005930", "삼성전자", StockNames.Source.KIWOOM);
        PositionBook positionBook = new PositionBook();
        positionBook.onFill(new Fill("k1", new BrokerOrderId("b1"), new StockCode("005930"), Side.BUY, new Quantity(10), new Price(new BigDecimal("70000")), Instant.now()));

        DashboardFacade facade = facade(positionBook, mock(EventFeed.class), mock(KillSwitch.class),
                mock(DailyLimitTracker.class), mock(DailyPnlTracker.class), mock(TradingSystemManager.class),
                new TradingProperties(TradingProperties.Mode.SIM, Duration.ofMinutes(5)), false, false);

        List<PositionView> views = facade.positions();

        assertEquals(1, views.size());
        assertEquals("005930", views.get(0).symbol());
        assertEquals("삼성전자", views.get(0).symbolName());
        assertEquals(10L, views.get(0).quantity());
        assertEquals(new BigDecimal("70000"), views.get(0).avgPrice());
    }

    @Test
    void trading_View는_상태기계_킬스위치_한도_손익을_그대로_조합한다() {
        TradingSystemManager tradingSystemManager = mock(TradingSystemManager.class);
        when(tradingSystemManager.status()).thenReturn(TradingSystemStatus.RUNNING);

        KillSwitch killSwitch = mock(KillSwitch.class);
        when(killSwitch.isEngaged()).thenReturn(true);

        DailyLimitTracker dailyLimitTracker = mock(DailyLimitTracker.class);
        when(dailyLimitTracker.todayOrderCount()).thenReturn(7);

        DailyPnlTracker dailyPnlTracker = mock(DailyPnlTracker.class);
        when(dailyPnlTracker.todayRealizedPnl()).thenReturn(new BigDecimal("-15000"));

        MacroGuard macroGuard = mock(MacroGuard.class);
        when(macroGuard.isConservativeMode()).thenReturn(true);

        DashboardFacade facade = facade(new PositionBook(), mock(EventFeed.class), killSwitch,
                dailyLimitTracker, dailyPnlTracker, macroGuard, tradingSystemManager,
                new TradingProperties(TradingProperties.Mode.SIM, Duration.ofMinutes(5)), false, false);

        TradingStatusView view = facade.trading();

        assertEquals(TradingSystemStatus.RUNNING, view.status());
        assertTrue(view.killSwitchEngaged());
        assertEquals(7, view.todayOrderCount());
        assertEquals(new BigDecimal("-15000"), view.todayRealizedPnl());
        assertTrue(view.conservativeMode(), "MacroGuard.isConservativeMode()를 그대로 조합해야 함");
    }

    @Test
    void system_View는_실행모드와_설정플래그를_그대로_조합한다() {
        DashboardFacade facade = facade(new PositionBook(), mock(EventFeed.class), mock(KillSwitch.class),
                mock(DailyLimitTracker.class), mock(DailyPnlTracker.class), mock(TradingSystemManager.class),
                new TradingProperties(TradingProperties.Mode.LIVE, Duration.ofMinutes(5)), true, false);

        SystemStatusView view = facade.system();

        assertEquals("LIVE", view.executionMode());
        assertEquals("MOCK", view.brokerServer());           // 2026-10-23 — LIVE여도 모의 서버면 실계좌가 아니다
        assertEquals(1_000_000, view.manualOrderMaxKrw()); // Phase 0.4 — 화면 확인 창이 같은 상한으로 미리 막는다
        assertTrue(view.c3Enabled());
        assertFalse(view.wsEnabled());
    }

    @Test
    void dashboard는_네_영역을_모두_조합한_DashboardView를_반환한다() {
        TradingSystemManager tradingSystemManager = mock(TradingSystemManager.class);
        when(tradingSystemManager.status()).thenReturn(TradingSystemStatus.STOPPED);
        DailyLimitTracker dailyLimitTracker = mock(DailyLimitTracker.class);
        DailyPnlTracker dailyPnlTracker = mock(DailyPnlTracker.class);
        when(dailyPnlTracker.todayRealizedPnl()).thenReturn(BigDecimal.ZERO);

        DashboardFacade facade = facade(new PositionBook(), mock(EventFeed.class), mock(KillSwitch.class),
                dailyLimitTracker, dailyPnlTracker, tradingSystemManager,
                new TradingProperties(TradingProperties.Mode.SIM, Duration.ofMinutes(5)), false, false);

        DashboardView view = facade.dashboard();

        assertTrue(view.positions().isEmpty());
        assertEquals(TradingSystemStatus.STOPPED, view.trading().status());
        assertEquals("SIM", view.system().executionMode());
    }

    // ── 2026-10-23: 계좌 잔고·보유 평가손익·일 손실 한도·접속 서버 ─────────────────────────

    @Test
    void 접속_서버는_REST_주소로_가린다() {
        assertEquals("MOCK", DashboardFacade.brokerServerOf("https://mockapi.kiwoom.com"));
        assertEquals("REAL", DashboardFacade.brokerServerOf("https://api.kiwoom.com"));
        assertEquals("UNKNOWN", DashboardFacade.brokerServerOf(""));
        assertEquals("UNKNOWN", DashboardFacade.brokerServerOf(null));
    }

    @Test
    void 보유_종목은_마지막_잔고의_현재가로_평가손익과_수익률을_보인다() {
        StockNames.learn("028050", "삼성E&A", StockNames.Source.KIWOOM);
        PositionBook positionBook = new PositionBook();
        positionBook.onFill(new Fill("k1", new BrokerOrderId("b1"), new StockCode("028050"), Side.BUY, new Quantity(17),
                new Price(new BigDecimal("55400")), Instant.now()));
        AccountSummaryService accountSummary = mock(AccountSummaryService.class);
        when(accountSummary.latestHoldings()).thenReturn(Map.of(new StockCode("028050"),
                new BrokerHolding(new StockCode("028050"), "삼성E&A", 17, new Price(new BigDecimal("55400")),
                        new Price(new BigDecimal("55400")), new Price(new BigDecimal("58100")))));

        DashboardFacade facade = facade(positionBook, mock(EventFeed.class), mock(KillSwitch.class),
                mock(DailyLimitTracker.class), mock(DailyPnlTracker.class), mock(MacroGuard.class),
                mock(TradingSystemManager.class), new TradingProperties(TradingProperties.Mode.LIVE, Duration.ofMinutes(5)),
                false, false, accountSummary);

        PositionView view = facade.positions().get(0);
        assertEquals(0, new BigDecimal("58100").compareTo(view.currentPrice()));
        assertEquals(0, new BigDecimal("45900").compareTo(view.evaluationPnl()));   // (58,100 − 55,400) × 17
        assertEquals(0, new BigDecimal("4.87").compareTo(view.returnPct()));        // 2,700 ÷ 55,400
    }

    @Test
    void 잔고를_모르는_종목은_현재가_없이_보인다() {
        PositionBook positionBook = new PositionBook();
        positionBook.onFill(new Fill("k1", new BrokerOrderId("b1"), new StockCode("028050"), Side.BUY, new Quantity(17),
                new Price(new BigDecimal("55400")), Instant.now()));

        DashboardFacade facade = facade(positionBook, mock(EventFeed.class), mock(KillSwitch.class),
                mock(DailyLimitTracker.class), mock(DailyPnlTracker.class), mock(TradingSystemManager.class),
                new TradingProperties(TradingProperties.Mode.LIVE, Duration.ofMinutes(5)), false, false);

        PositionView view = facade.positions().get(0);
        assertNull(view.currentPrice());
        assertNull(view.evaluationPnl());
        assertNull(view.returnPct());
    }

    @Test
    void dashboard는_계좌_잔고와_일_손실_한도를_싣는다() {
        AccountSummaryService accountSummary = mock(AccountSummaryService.class);
        AccountView account = new AccountView(true, new BigDecimal("490000000"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, Instant.now(), null, 20_000_000, BigDecimal.ZERO, BigDecimal.ZERO);
        when(accountSummary.account()).thenReturn(account);
        DailyPnlTracker dailyPnlTracker = mock(DailyPnlTracker.class);
        when(dailyPnlTracker.todayRealizedPnl()).thenReturn(BigDecimal.ZERO);
        when(dailyPnlTracker.limitStatus(new BigDecimal("490000000"))).thenReturn(new DailyPnlTracker.LimitStatus(-0.05, new BigDecimal("20000000"),
                new BigDecimal("-1000000"), new BigDecimal("11405"), true, new BigDecimal("20000000"), BigDecimal.ZERO));
        TradingSystemManager tradingSystemManager = mock(TradingSystemManager.class);
        when(tradingSystemManager.status()).thenReturn(TradingSystemStatus.RUNNING);

        DashboardFacade facade = facade(new PositionBook(), mock(EventFeed.class), mock(KillSwitch.class),
                mock(DailyLimitTracker.class), dailyPnlTracker, mock(MacroGuard.class), tradingSystemManager,
                new TradingProperties(TradingProperties.Mode.LIVE, Duration.ofMinutes(5)), false, false, accountSummary);

        DashboardView view = facade.dashboard();

        assertSame(account, view.account());
        assertEquals(-0.05, view.trading().dailyLossLimit().lossPct());
        assertEquals(0, new BigDecimal("-1000000").compareTo(view.trading().dailyLossLimit().threshold()));
        assertEquals(0, new BigDecimal("20000000").compareTo(view.trading().dailyLossLimit().base()));
    }

    @Test
    void 일_손실_한도를_모르면_null이다() {
        DashboardFacade facade = facade(new PositionBook(), mock(EventFeed.class), mock(KillSwitch.class),
                mock(DailyLimitTracker.class), mock(DailyPnlTracker.class), mock(TradingSystemManager.class),
                new TradingProperties(TradingProperties.Mode.SIM, Duration.ofMinutes(5)), false, false);

        assertNull(facade.trading().dailyLossLimit());
    }
}

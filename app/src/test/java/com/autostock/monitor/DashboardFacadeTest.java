package com.autostock.monitor;

import com.autostock.common.event.Fill;
import com.autostock.common.event.Side;
import com.autostock.trading.TradingProperties;
import com.autostock.monitor.view.DashboardView;
import com.autostock.monitor.view.PositionView;
import com.autostock.monitor.view.SystemStatusView;
import com.autostock.monitor.view.TradingStatusView;
import com.autostock.risk.DailyLimitTracker;
import com.autostock.risk.DailyPnlTracker;
import com.autostock.risk.DisclosureBlacklist;
import com.autostock.risk.KillSwitch;
import com.autostock.risk.MacroGuard;
import com.autostock.portfolio.PositionBook;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        return new DashboardFacade(positionBook, eventFeed, killSwitch, dailyLimitTracker, dailyPnlTracker,
                macroGuard, mock(DisclosureBlacklist.class), tradingSystemManager, executionProperties,
                new SlippageTracker(java.time.Clock.systemUTC(), new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                c3Enabled, wsEnabled);
    }

    @Test
    void positions는_PositionBook_스냅샷을_View로_옮겨담는다() {
        PositionBook positionBook = new PositionBook();
        positionBook.onFill(new Fill("k1", "b1", "005930", Side.BUY, 10, new BigDecimal("70000"), Instant.now()));

        DashboardFacade facade = facade(positionBook, mock(EventFeed.class), mock(KillSwitch.class),
                mock(DailyLimitTracker.class), mock(DailyPnlTracker.class), mock(TradingSystemManager.class),
                new TradingProperties(TradingProperties.Mode.SIM, Duration.ofMinutes(5)), false, false);

        List<PositionView> views = facade.positions();

        assertEquals(1, views.size());
        assertEquals("005930", views.get(0).symbol());
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
}

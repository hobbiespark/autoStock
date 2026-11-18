package com.autostock.monitor;

import com.autostock.common.event.Fill;
import com.autostock.common.event.Side;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import com.autostock.common.util.StockCode;
import com.autostock.common.util.StockNames;
import com.autostock.execution.BrokerBalance;
import com.autostock.execution.BrokerHolding;
import com.autostock.execution.BrokerPort;
import com.autostock.market.MarketCalendarService;
import com.autostock.portfolio.PositionBook;
import com.autostock.risk.DailyLimitTracker;
import com.autostock.risk.DailyPnlTracker;
import com.autostock.risk.DisclosureBlacklist;
import com.autostock.risk.KillSwitch;
import com.autostock.risk.MacroGuard;
import com.autostock.risk.OpenOrderQuery;
import com.autostock.risk.TradingCapitalProperties;
import com.autostock.trading.TradingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VALUNIVO 910: 텔레그램·플랫폼의 보유 종목 명령이 대시보드를 열지 않아도 현재가를 채운다.
 * 실제 {@link AccountSummaryService}·{@link DashboardFacade}·{@link AppCommandRunner}를 잇고 키움 잔고 조회만 모의로 둔다(조립 경로, RM-54).
 */
class AppCommandRunnerPositionsTest {

    private static final StockCode KAKAO = new StockCode("035720");
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-18T06:20:00Z"), ZoneOffset.UTC);   // 15:20 KST
    private final BrokerPort brokerPort = mock(BrokerPort.class);
    private final PositionBook positionBook = new PositionBook();

    private AppCommandRunner runner(TradingProperties.Mode mode) {
        MarketCalendarService calendar = mock(MarketCalendarService.class);
        when(calendar.isTradingDay(any())).thenReturn(true);
        OpenOrderQuery openOrders = mock(OpenOrderQuery.class);
        when(openOrders.openBuyAmount()).thenReturn(BigDecimal.ZERO);
        TradingProperties trading = new TradingProperties(mode, Duration.ofMinutes(5));
        AccountSummaryService accountSummary = new AccountSummaryService(brokerPort, trading, calendar, positionBook, openOrders,
                new TradingCapitalProperties(20_000_000, 2.0), clock);
        KillSwitch killSwitch = mock(KillSwitch.class);
        DashboardFacade facade = new DashboardFacade(positionBook, mock(EventFeed.class), killSwitch, mock(DailyLimitTracker.class),
                mock(DailyPnlTracker.class), mock(MacroGuard.class), mock(DisclosureBlacklist.class), mock(TradingSystemManager.class),
                trading, new SlippageTracker(clock, new SimpleMeterRegistry()), false, false, 1_000_000, accountSummary,
                "https://mockapi.kiwoom.com");
        return new AppCommandRunner(new TelegramStockCommands(killSwitch, positionBook, clock), killSwitch, facade);
    }

    private void holdKakao() {
        StockNames.learn("035720", "카카오", StockNames.Source.KIWOOM);
        positionBook.onFill(new Fill("k1", new BrokerOrderId("b1"), KAKAO, Side.BUY, new Quantity(6),
                new Price(new BigDecimal("31933")), clock.instant()));
    }

    @Test
    void 대시보드를_열지_않아도_보유_종목_명령이_잔고를_읽어_현재가를_채운다() {
        holdKakao();
        when(brokerPort.balance()).thenReturn(new BrokerBalance(new BigDecimal("209400"), new BigDecimal("191598"),
                new BigDecimal("17802"), new BigDecimal("1000000"), List.of(new BrokerHolding(KAKAO, "카카오", 6,
                        new Price(new BigDecimal("31933")), new Price(new BigDecimal("31933")), new Price(new BigDecimal("34900"))))));

        AppCommandRunner.Result result = runner(TradingProperties.Mode.LIVE).run("positions_view", "", AppCommandRunner.Route.APP_BOT);

        assertTrue(result.ok());
        assertTrue(result.text().contains("34,900"), result.text());                  // 현재가
        assertTrue(result.text().contains("17,802"), result.text());                  // (34,900 − 31,933) × 6
        assertTrue(result.text().contains("현재가 기준: 2026-11-18 15:20:00 KST"), result.text());
    }

    @Test
    void 잔고는_30초_안에_다시_부르면_조회하지_않는다() {
        holdKakao();
        when(brokerPort.balance()).thenReturn(new BrokerBalance(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("1000000"), List.of()));
        AppCommandRunner runner = runner(TradingProperties.Mode.LIVE);

        runner.run("positions_view", "", AppCommandRunner.Route.APP_BOT);
        runner.run("positions_view", "", AppCommandRunner.Route.PLATFORM);

        verify(brokerPort, times(1)).balance();
    }

    @Test
    void 잔고_조회가_실패하면_사유를_한_줄로_보인다() {
        holdKakao();
        when(brokerPort.balance()).thenThrow(new IllegalStateException("모의 서버 점검"));

        AppCommandRunner.Result result = runner(TradingProperties.Mode.LIVE).run("positions_view", "", AppCommandRunner.Route.APP_BOT);

        assertTrue(result.ok());
        assertTrue(result.text().contains("메모: 잔고 조회 실패 — 모의 서버 점검"), result.text());
        assertFalse(result.text().contains("현재가 기준"), result.text());
    }

    @Test
    void 보유가_없으면_예전과_같은_한_줄() {
        AppCommandRunner.Result result = runner(TradingProperties.Mode.SIM).run("positions_view", "", AppCommandRunner.Route.APP_BOT);

        assertEquals("보유 종목이 없습니다.", result.text());
    }
}

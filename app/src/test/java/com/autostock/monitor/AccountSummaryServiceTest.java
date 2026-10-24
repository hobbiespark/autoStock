package com.autostock.monitor;

import com.autostock.common.event.Fill;
import com.autostock.common.event.Side;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import com.autostock.common.util.StockCode;
import com.autostock.execution.BrokerBalance;
import com.autostock.execution.BrokerHolding;
import com.autostock.execution.BrokerPort;
import com.autostock.market.MarketCalendarService;
import com.autostock.monitor.view.AccountView;
import com.autostock.portfolio.PositionBook;
import com.autostock.risk.OpenOrderQuery;
import com.autostock.risk.TradingCapitalProperties;
import com.autostock.trading.TradingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 대시보드 계좌 잔고(2026-10-23) — 브로커 조회 주기(거래일 30초·휴장일 10분), 실패 때 마지막 값, 운용 자금 사용액.
 */
class AccountSummaryServiceTest {

    private static final StockCode SAMSUNG_EA = new StockCode("028050");

    private final BrokerPort brokerPort = mock(BrokerPort.class);
    private final MarketCalendarService calendar = mock(MarketCalendarService.class);
    private final OpenOrderQuery openOrderQuery = mock(OpenOrderQuery.class);
    private final PositionBook positionBook = new PositionBook();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-23T00:10:00Z"));   // 09:10 KST

    @BeforeEach
    void setUp() {
        when(calendar.isTradingDay(any())).thenReturn(true);
        when(openOrderQuery.openBuyAmount()).thenReturn(new BigDecimal("500000"));
    }

    private AccountSummaryService service(TradingProperties.Mode mode) {
        return new AccountSummaryService(brokerPort, new TradingProperties(mode, Duration.ofMinutes(5)), calendar,
                positionBook, openOrderQuery, new TradingCapitalProperties(20_000_000, 2.0), clock);
    }

    private static BrokerBalance balance(String totalAsset, BrokerHolding... holdings) {
        return new BrokerBalance(new BigDecimal("987700"), new BigDecimal("941800"), new BigDecimal("45900"),
                new BigDecimal(totalAsset), List.of(holdings));
    }

    @Test
    void SIM이면_브로커를_부르지_않는다() {
        AccountView view = service(TradingProperties.Mode.SIM).account();

        assertFalse(view.available());
        assertNull(view.totalAsset());
        assertEquals("SIM — 브로커 잔고 없음", view.note());
        verify(brokerPort, never()).balance();
    }

    @Test
    void 보유가_없어도_계좌_총자산을_보인다() {
        when(brokerPort.balance()).thenReturn(new BrokerBalance(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("490011405"), List.of()));

        AccountView view = service(TradingProperties.Mode.LIVE).account();

        assertTrue(view.available());
        assertEquals(0, new BigDecimal("490011405").compareTo(view.totalAsset()));
        assertNull(view.note());
        assertEquals(20_000_000, view.capitalLimitKrw());
        assertEquals(0, BigDecimal.ZERO.compareTo(view.capitalHeldCost()));
        assertEquals(0, new BigDecimal("500000").compareTo(view.capitalPendingBuy()));
    }

    @Test
    void 거래일에는_30초_안에_다시_부르면_마지막_값을_준다() {
        when(brokerPort.balance()).thenReturn(balance("490000000"));
        AccountSummaryService service = service(TradingProperties.Mode.LIVE);

        service.account();
        clock.advance(29);
        service.account();
        verify(brokerPort, times(1)).balance();

        clock.advance(2);
        service.account();
        verify(brokerPort, times(2)).balance();
    }

    @Test
    void 휴장일에는_10분마다만_부른다() {
        when(calendar.isTradingDay(any())).thenReturn(false);
        when(brokerPort.balance()).thenReturn(balance("490000000"));
        AccountSummaryService service = service(TradingProperties.Mode.LIVE);

        service.account();
        clock.advance(599);
        service.account();
        verify(brokerPort, times(1)).balance();
    }

    @Test
    void 조회가_실패하면_마지막_값을_사유와_함께_보인다() {
        when(brokerPort.balance()).thenReturn(balance("490000000")).thenThrow(new IllegalStateException("8005 서버 점검"));
        AccountSummaryService service = service(TradingProperties.Mode.LIVE);
        service.account();
        clock.advance(31);

        AccountView view = service.account();

        assertTrue(view.available());
        assertEquals(0, new BigDecimal("490000000").compareTo(view.totalAsset()));
        assertEquals("잔고 조회 실패 — 8005 서버 점검 (마지막으로 읽은 값)", view.note());
    }

    @Test
    void 운용_자금_사용액은_앱_장부의_보유_매수_원가와_미체결_매수다() {
        positionBook.onFill(new Fill("k1", new BrokerOrderId("b1"), SAMSUNG_EA, Side.BUY, new Quantity(17),
                new Price(new BigDecimal("55400")), clock.instant()));
        when(brokerPort.balance()).thenReturn(balance("490000000", new BrokerHolding(SAMSUNG_EA, "삼성E&A", 17,
                new Price(new BigDecimal("55400")), null, new Price(new BigDecimal("58100")))));
        AccountSummaryService service = service(TradingProperties.Mode.LIVE);

        AccountView view = service.account();

        assertEquals(0, new BigDecimal("941800").compareTo(view.capitalHeldCost()));   // 17 × 55,400
        assertEquals(0, new BigDecimal("58100").compareTo(service.latestHoldings().get(SAMSUNG_EA).currentPrice().value()));
    }

    @Test
    void 미체결_매수를_못_세면_null이다() {
        when(openOrderQuery.openBuyAmount()).thenThrow(new IllegalStateException("DB"));

        assertNull(service(TradingProperties.Mode.SIM).account().capitalPendingBuy());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

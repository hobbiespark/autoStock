package com.autostock.trading;

import com.autostock.common.event.PositionMismatch;
import com.autostock.common.event.PositionRestored;
import com.autostock.common.util.Price;
import com.autostock.common.util.StockCode;
import com.autostock.execution.BrokerBalance;
import com.autostock.execution.BrokerHolding;
import com.autostock.execution.BrokerPort;
import com.autostock.market.MarketSessionService;
import com.autostock.portfolio.PositionBook;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 잔고 ↔ 장부 주기 대사(실행 계획 1.5) — 일치·수량 불일치·장부에만 있음·브로커에만 있음, 연속 2회 확인, 한 번만 알림,
 * 다시 일치하면 풀림, 켜짐·LIVE·장중 조건, 게이지.
 */
class PositionReconcilerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T01:00:00Z"), ZoneOffset.UTC);
    private static final StockCode SAMSUNG = new StockCode("005930");
    private static final StockCode HYNIX = new StockCode("000660");

    private final BrokerPort brokerPort = mock(BrokerPort.class);
    private final MarketSessionService marketSession = mock(MarketSessionService.class);
    private final PositionBook positionBook = new PositionBook();
    private final List<Object> events = new ArrayList<>();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private PositionReconciler reconciler;

    @BeforeEach
    void setUp() {
        when(marketSession.isActive()).thenReturn(true);
        reconciler = reconciler(true, TradingProperties.Mode.LIVE);
    }

    private PositionReconciler reconciler(boolean enabled, TradingProperties.Mode mode) {
        return new PositionReconciler(brokerPort, positionBook, new TradingProperties(mode, Duration.ofMinutes(5)),
                new PositionReconcileProperties(enabled), marketSession, events::add, CLOCK, registry);
    }

    private void broker(BrokerHolding... holdings) {
        when(brokerPort.balance()).thenReturn(new BrokerBalance(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, List.of(holdings)));
    }

    private static BrokerHolding holding(StockCode symbol, long quantity) {
        return new BrokerHolding(symbol, "", quantity, new Price(new BigDecimal("1000")));
    }

    private void book(StockCode symbol, long quantity) {
        positionBook.onPositionRestored(new PositionRestored(symbol, quantity, new Price(new BigDecimal("1000"))));
    }

    private double gauge() {
        return registry.get("reconcile.position.mismatch").gauge().value();
    }

    @Test
    void 일치하면_알리지_않고_게이지는_0이다() {
        book(SAMSUNG, 19);
        broker(holding(SAMSUNG, 19));

        reconciler.scheduledCheck();
        reconciler.scheduledCheck();

        assertTrue(events.isEmpty());
        assertEquals(0.0, gauge());
    }

    @Test
    void 같은_수량_불일치를_연속_2회_보면_한_번만_알린다() {
        book(SAMSUNG, 10);
        broker(holding(SAMSUNG, 19));

        reconciler.scheduledCheck();
        assertTrue(events.isEmpty(), "1회째는 체결 반영 시차일 수 있다");
        assertEquals(1.0, gauge());

        reconciler.scheduledCheck();
        reconciler.scheduledCheck();
        assertEquals(List.of(new PositionMismatch(SAMSUNG, 19, 10, CLOCK.instant())), events);
    }

    @Test
    void 장부에만_있거나_브로커에만_있는_종목도_불일치다() {
        book(SAMSUNG, 19);           // 브로커에는 없음(HTS 매도 등)
        broker(holding(HYNIX, 3));   // 장부에는 없음(체결 통보 유실 등)

        reconciler.scheduledCheck();
        reconciler.scheduledCheck();

        assertEquals(2, events.size());
        assertTrue(events.contains(new PositionMismatch(SAMSUNG, 0, 19, CLOCK.instant())));
        assertTrue(events.contains(new PositionMismatch(HYNIX, 3, 0, CLOCK.instant())));
        assertEquals(2.0, gauge());
    }

    @Test
    void 한_번만_보인_불일치는_알리지_않는다() {
        book(SAMSUNG, 19);
        broker(holding(SAMSUNG, 10));
        reconciler.scheduledCheck();

        broker(holding(SAMSUNG, 19));   // 다음 회차에 맞춰짐
        reconciler.scheduledCheck();

        assertTrue(events.isEmpty());
        assertEquals(0.0, gauge());
    }

    @Test
    void 다시_일치하면_풀리고_같은_불일치가_또_생기면_다시_알린다() {
        book(SAMSUNG, 10);
        broker(holding(SAMSUNG, 19));
        reconciler.scheduledCheck();
        reconciler.scheduledCheck();
        assertEquals(1, events.size());

        broker(holding(SAMSUNG, 10));
        reconciler.scheduledCheck();
        broker(holding(SAMSUNG, 19));
        reconciler.scheduledCheck();
        reconciler.scheduledCheck();

        assertEquals(2, events.size());
    }

    @Test
    void 꺼져_있거나_SIM이거나_장외면_잔고를_조회하지_않는다() {
        reconciler(false, TradingProperties.Mode.LIVE).scheduledCheck();
        reconciler(true, TradingProperties.Mode.SIM).scheduledCheck();
        when(marketSession.isActive()).thenReturn(false);
        reconciler(true, TradingProperties.Mode.LIVE).scheduledCheck();

        verify(brokerPort, never()).balance();
    }

    @Test
    void 잔고_조회가_실패해도_예외를_던지지_않는다() {
        when(brokerPort.balance()).thenThrow(new IllegalStateException("모의 서버 점검(테스트)"));

        assertDoesNotThrow(() -> reconciler.scheduledCheck());
        assertTrue(events.isEmpty());
        assertEquals(1.0, registry.counter("reconcile.failure", "kind", "positions").count());
    }
}

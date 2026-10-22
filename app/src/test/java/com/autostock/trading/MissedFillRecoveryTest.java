package com.autostock.trading;

import com.autostock.common.event.Fill;
import com.autostock.common.event.Side;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.ClientOrderId;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import com.autostock.common.util.StockCode;
import com.autostock.execution.BrokerBalance;
import com.autostock.execution.BrokerHolding;
import com.autostock.execution.BrokerOutstandingOrder;
import com.autostock.execution.BrokerPort;
import com.autostock.portfolio.PositionBook;
import com.autostock.portfolio.StrategyBook;
import com.autostock.portfolio.StrategyFillRepository;
import com.autostock.portfolio.StrategyPositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 체결통보 누락 복구 — 10/16 15:26 ON-CLOSE 매수 3건이 체결통보 REG 거절로 장부에 안 들어간 사례(2026-10-16).
 */
class MissedFillRecoveryTest {

    /** 10/16 15:26 KST 접수 → 복구는 그날 15:45. */
    private static final Instant SUBMITTED_AT = Instant.parse("2026-10-16T06:26:00Z");
    private static final Instant AFTER_CLOSE = Instant.parse("2026-10-16T06:45:00Z");
    private static final StockCode HYUNDAI = new StockCode("005380");
    private static final StockCode CELLTRION = new StockCode("068270");

    private OrderRepository orders;
    private BrokerPort broker;
    private PositionBook positionBook;
    private StrategyBook strategyBook;
    private final List<Object> published = new ArrayList<>();
    private final Map<String, OrderEntity> byClientId = new HashMap<>();
    private final List<BrokerHolding> holdings = new ArrayList<>();
    private final List<BrokerOutstandingOrder> outstanding = new ArrayList<>();
    private MissedFillRecovery recovery;
    private int seq;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);
        when(orders.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orders.findByStatusIn(anyCollection())).thenAnswer(inv -> byClientId.values().stream()
                .filter(o -> MissedFillRecovery.OPEN_STATUSES.contains(o.getStatus())).toList());
        when(orders.findByClientOrderId(any())).thenAnswer(inv -> Optional.ofNullable(byClientId.get(inv.getArgument(0))));
        broker = mock(BrokerPort.class);
        when(broker.outstandingOrders()).thenAnswer(inv -> outstanding);
        when(broker.balance()).thenAnswer(inv -> new BrokerBalance(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, holdings));
        positionBook = mock(PositionBook.class);
        StrategyPositionRepository positionRepo = mock(StrategyPositionRepository.class);
        when(positionRepo.findAll()).thenReturn(List.of());
        strategyBook = new StrategyBook(positionRepo, mock(StrategyFillRepository.class),
                Clock.fixed(AFTER_CLOSE, ZoneOffset.UTC), 0.00015, 0.0020);
        recovery = recoveryAt(AFTER_CLOSE, TradingProperties.Mode.LIVE);
    }

    private MissedFillRecovery recoveryAt(Instant now, TradingProperties.Mode mode) {
        return new MissedFillRecovery(orders, broker, positionBook, strategyBook, published::add,
                new TradingProperties(mode, Duration.ofMinutes(5)), Clock.fixed(now, ZoneOffset.UTC));
    }

    private OrderEntity submitted(String strategyId, StockCode symbol, Side side, long qty, String limit, String brokerNo) {
        String id = ClientOrderId.generate(LocalDate.of(2026, 10, 16), strategyId, symbol.value(), side, ++seq).value();
        OrderEntity e = new OrderEntity(id, symbol, side, new Quantity(qty), new Price(new BigDecimal(limit)), strategyId,
                SUBMITTED_AT);
        e.transitionTo(OrderStatus.VALIDATED, SUBMITTED_AT);
        e.transitionTo(OrderStatus.SUBMITTING, SUBMITTED_AT);
        e.markSubmitted(new BrokerOrderId(brokerNo), SUBMITTED_AT);
        byClientId.put(id, e);
        return e;
    }

    private void brokerHolds(StockCode symbol, long qty, String avg) {
        holdings.add(new BrokerHolding(symbol, symbol.value(), qty, new Price(new BigDecimal(avg)), null));
    }

    @Test
    void 재기동_뒤에는_계좌는_잔고복원이_맞춰_뒀으므로_전략_장부에만_넣고_주문을_닫는다() {
        OrderEntity h = submitted("ON-CLOSE", HYUNDAI, Side.BUY, 15, "328000", "0170073");
        OrderEntity c = submitted("ON-CLOSE", CELLTRION, Side.BUY, 24, "203500", "0170076");
        brokerHolds(HYUNDAI, 15, "312500");
        brokerHolds(CELLTRION, 24, "193500");
        when(positionBook.get(HYUNDAI)).thenReturn(new PositionBook.Position(15, new BigDecimal("312500")));
        when(positionBook.get(CELLTRION)).thenReturn(new PositionBook.Position(24, new BigDecimal("193500")));

        assertEquals(2, recovery.recover("기동"));

        assertEquals(OrderStatus.FILLED, h.getStatus());
        assertEquals(OrderStatus.FILLED, c.getStatus());
        assertEquals(15, strategyBook.position("ON-CLOSE", HYUNDAI).orElseThrow().quantity());
        assertEquals(0, new BigDecimal("312500").compareTo(strategyBook.position("ON-CLOSE", HYUNDAI).orElseThrow().avgPrice()));
        assertEquals(24, strategyBook.position("ON-CLOSE", CELLTRION).orElseThrow().quantity());
        assertTrue(published.isEmpty(), "계좌 장부는 이미 맞다 — Fill을 발행하면 이중계상");
    }

    @Test
    void 앱이_계속_켜져_있었으면_Fill을_발행해_계좌_장부도_같이_맞춘다() {
        submitted("ON-CLOSE", HYUNDAI, Side.BUY, 15, "328000", "0170073");
        brokerHolds(HYUNDAI, 15, "312500");

        assertEquals(1, recovery.recover("장 마감 뒤"));

        assertEquals(1, published.size());
        Fill fill = (Fill) published.get(0);
        assertEquals(15, fill.filledQuantity().value());
        assertEquals(Side.BUY, fill.side());
        assertEquals(0, new BigDecimal("312500").compareTo(fill.fillPrice().value()));
        assertEquals(SUBMITTED_AT, fill.timestamp());
    }

    @Test
    void 브로커가_안_들고_있으면_체결로_보지_않고_장_마감_뒤면_당일_소멸로_닫는다() {
        OrderEntity h = submitted("ON-CLOSE", HYUNDAI, Side.BUY, 15, "328000", "0170073");

        assertEquals(0, recovery.recover("장 마감 뒤"));
        assertEquals(OrderStatus.CANCELLED, h.getStatus());
        assertEquals(0, h.getFilledQuantity());
        assertTrue(published.isEmpty());
    }

    @Test
    void 장중에는_설명_안_되는_주문을_닫지_않는다() {
        Instant at1500 = Instant.parse("2026-10-16T06:00:00Z");
        String id = ClientOrderId.generate(LocalDate.of(2026, 10, 16), "VB-DAY", "005380", Side.BUY, 9).value();
        OrderEntity e = new OrderEntity(id, HYUNDAI, Side.BUY, new Quantity(5), new Price(new BigDecimal("320000")),
                "VB-DAY", at1500.minus(Duration.ofMinutes(10)));
        e.transitionTo(OrderStatus.VALIDATED, e.getSubmittedAt());
        e.transitionTo(OrderStatus.SUBMITTING, e.getSubmittedAt());
        e.markSubmitted(new BrokerOrderId("0100001"), e.getSubmittedAt());
        byClientId.put(id, e);

        assertEquals(0, recoveryAt(at1500, TradingProperties.Mode.LIVE).recover("기동"));
        assertEquals(OrderStatus.SUBMITTED, e.getStatus());
    }

    @Test
    void 장종료_취소_실패로_UNKNOWN이_된_주문도_복구한다() {
        // 10/16 15:31 타임아웃 취소 → RC4058 모의투자 장종료 → UNKNOWN(실제 상태)
        OrderEntity h = submitted("ON-CLOSE", HYUNDAI, Side.BUY, 74, "328000", "0170073");
        h.transitionTo(OrderStatus.UNKNOWN, SUBMITTED_AT);
        brokerHolds(HYUNDAI, 74, "313500");
        when(positionBook.get(HYUNDAI)).thenReturn(new PositionBook.Position(74, new BigDecimal("313500")));

        assertEquals(1, recovery.recover("기동"));

        assertEquals(OrderStatus.FILLED, h.getStatus());
        assertEquals(74, strategyBook.position("ON-CLOSE", HYUNDAI).orElseThrow().quantity());
    }

    @Test
    void 일부만_체결됐으면_체결분을_넣고_나머지는_소멸로_닫는다() {
        OrderEntity h = submitted("ON-CLOSE", HYUNDAI, Side.BUY, 15, "328000", "0170073");
        brokerHolds(HYUNDAI, 10, "312500");

        assertEquals(1, recovery.recover("장 마감 뒤"));

        assertEquals(OrderStatus.CANCELLED, h.getStatus());
        assertEquals(10, h.getFilledQuantity());
        assertEquals(10, ((Fill) published.get(0)).filledQuantity().value());
    }

    @Test
    void 브로커_미체결_목록에_있으면_건드리지_않는다() {
        OrderEntity h = submitted("ON-CLOSE", HYUNDAI, Side.BUY, 15, "328000", "0170073");
        brokerHolds(HYUNDAI, 15, "312500");
        outstanding.add(new BrokerOutstandingOrder(new BrokerOrderId("170073"), HYUNDAI, Side.BUY, 15, 15));

        assertEquals(0, recovery.recover("장 마감 뒤"));
        assertEquals(OrderStatus.SUBMITTED, h.getStatus());
        verify(broker, never()).balance();
    }

    @Test
    void 접수한_지_얼마_안_된_주문은_통보가_오는_중일_수_있어_기다린다() {
        OrderEntity h = submitted("ON-CLOSE", HYUNDAI, Side.BUY, 15, "328000", "0170073");
        brokerHolds(HYUNDAI, 15, "312500");

        assertEquals(0, recoveryAt(SUBMITTED_AT.plus(Duration.ofMinutes(1)), TradingProperties.Mode.LIVE).recover("기동"));
        assertEquals(OrderStatus.SUBMITTED, h.getStatus());
    }

    @Test
    void 다른_전략이_이미_설명하는_수량은_다시_넣지_않는다() {
        strategyBook.onFill(new Fill(ClientOrderId.generate(LocalDate.of(2026, 10, 15), "C3-MOMENTUM", "005380",
                Side.BUY, 1).value(), new BrokerOrderId("1"), HYUNDAI, Side.BUY, new Quantity(10),
                new Price(new BigDecimal("300000")), SUBMITTED_AT));
        OrderEntity h = submitted("ON-CLOSE", HYUNDAI, Side.BUY, 15, "328000", "0170073");
        brokerHolds(HYUNDAI, 10, "300000");   // ON-CLOSE 주문은 안 샀다(C3 보유만)
        when(positionBook.get(HYUNDAI)).thenReturn(new PositionBook.Position(10, new BigDecimal("300000")));

        assertEquals(0, recovery.recover("기동"));
        assertEquals(OrderStatus.CANCELLED, h.getStatus());   // 장 마감 뒤 — 안 산 주문은 당일 소멸
        assertTrue(strategyBook.position("ON-CLOSE", HYUNDAI).isEmpty());
    }

    @Test
    void 매도_통보가_빠졌으면_전략_장부에서_뺀다() {
        strategyBook.onFill(new Fill(ClientOrderId.generate(LocalDate.of(2026, 10, 15), "ON-CLOSE", "005380",
                Side.BUY, 1).value(), new BrokerOrderId("1"), HYUNDAI, Side.BUY, new Quantity(15),
                new Price(new BigDecimal("312500")), SUBMITTED_AT));
        OrderEntity s = submitted("ON-CLOSE", HYUNDAI, Side.SELL, 15, "306000", "0000123");
        // 브로커 0주, 계좌 장부는 재기동 복원으로 0주(=맞음)

        assertEquals(1, recovery.recover("기동"));

        assertEquals(OrderStatus.FILLED, s.getStatus());
        assertTrue(strategyBook.position("ON-CLOSE", HYUNDAI).isEmpty());
        assertTrue(published.isEmpty());
    }

    @Test
    void SIM이면_돌지_않는다() {
        submitted("ON-CLOSE", HYUNDAI, Side.BUY, 15, "328000", "0170073");
        brokerHolds(HYUNDAI, 15, "312500");

        recoveryAt(AFTER_CLOSE, TradingProperties.Mode.SIM).afterClose();

        verify(broker, never()).outstandingOrders();
    }
}

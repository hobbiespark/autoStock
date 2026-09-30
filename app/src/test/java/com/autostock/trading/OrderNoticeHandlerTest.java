package com.autostock.trading;

import com.autostock.common.event.Fill;
import com.autostock.common.event.OrderNotice;
import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Side;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import com.autostock.common.util.StockCode;
import com.autostock.execution.BrokerOrderResult;
import com.autostock.execution.BrokerPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OrderNoticeHandler 단위테스트.
 * TradingServiceTest와 같은 스타일: Spring 컨텍스트 없이 직접 조립하고,
 * ApplicationEventPublisher는 List::add로 대체해 발행된 이벤트를 그대로 확인한다.
 * BrokerPort/OrderRepository/ReconciliationService는 Mockito 목으로 대체한다.
 */
class OrderNoticeHandlerTest {

    private static final String BROKER_ORDER_ID = "BROKER-1";

    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher publisher = published::add;

    private OrderRepository orderRepository;
    private TradingService tradingService;
    private OrderNoticeHandler handler;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        when(orderRepository.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        BrokerPort brokerPort = mock(BrokerPort.class);
        when(brokerPort.placeOrder(any(OrderRequest.class))).thenReturn(new BrokerOrderResult(new BrokerOrderId(BROKER_ORDER_ID)));
        ReconciliationService reconciliationService = mock(ReconciliationService.class);

        tradingService = new TradingService(
                new TradingProperties(TradingProperties.Mode.LIVE, Duration.ofMinutes(5)),
                brokerPort, orderRepository, reconciliationService, publisher, new SimpleMeterRegistry(), Clock.systemUTC());
        handler = new OrderNoticeHandler(tradingService, orderRepository, publisher, clock);
    }

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-30T01:00:00Z"));

    /** 보류 만료를 결정론적으로 검증하기 위한 시계. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
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
            return now;
        }
    }

    private OrderRequest order(String idempotencyKey) {
        return new OrderRequest(idempotencyKey, "test-strategy", new StockCode("005930"), Side.BUY,
                new Quantity(10), new Price(new BigDecimal("70000")), Instant.now());
    }

    private OrderNotice notice(String status, long filledQuantity, BigDecimal fillPrice) {
        // remainingQuantity(FID 902)를 명시하지 않는 기존 테스트들은 -1(알 수 없음)로 둔다 —
        // 902 기반 FILLED 강제 로직(OrderNoticeHandler)이 개입하지 않고 기존 applyFill 계산만 작동한다.
        return notice(status, filledQuantity, fillPrice, -1L);
    }

    private OrderNotice notice(String status, long filledQuantity, BigDecimal fillPrice, long remainingQuantity) {
        // 파서(RealMessageParser)와 같은 번역 — 빈 값·0은 "체결가 없음"(null)
        Price price = fillPrice == null || fillPrice.signum() <= 0 ? null : new Price(fillPrice);
        return new OrderNotice(new BrokerOrderId(BROKER_ORDER_ID), new StockCode("005930"), status, filledQuantity, price,
                remainingQuantity, "00", Instant.now());
    }

    /** 표준 SUBMITTED 엔티티를 만들어 DB 폴백(findByBrokerOrderId)에 스텁한다. */
    private OrderEntity stubEntity(String clientOrderId, Side side, long quantity) {
        OrderEntity entity = new OrderEntity(clientOrderId, new StockCode("005930"), side,
                new Quantity(quantity), new Price(new BigDecimal("70000")), "test-strategy");
        entity.transitionTo(OrderStatus.VALIDATED);
        entity.transitionTo(OrderStatus.SUBMITTING);
        entity.markSubmitted(new BrokerOrderId(BROKER_ORDER_ID));
        when(orderRepository.findByBrokerOrderId(new BrokerOrderId(BROKER_ORDER_ID))).thenReturn(Optional.of(entity));
        return entity;
    }

    @Test
    void 체결_통보_수신시_Fill_발행() {
        tradingService.onOrderRequest(order("key-1")); // brokerOrderId 매핑 생성(인메모리)
        // 누적→증분 변환(운영 1일차 ⑥) 이후 증분 계산 기준인 OrderEntity가 필수다 —
        // 엔티티 없는 체결통보는 이중계상 방지를 위해 무시된다(별도 테스트).
        stubEntity("key-1", Side.BUY, 10);

        handler.onOrderNotice(notice("체결", 10, new BigDecimal("70100")));

        assertEquals(1, published.size());
        Fill fill = (Fill) published.get(0);
        assertEquals("key-1", fill.orderIdempotencyKey());
        assertEquals(new BrokerOrderId(BROKER_ORDER_ID), fill.brokerOrderId());
        assertEquals(new StockCode("005930"), fill.symbol());
        assertEquals(Side.BUY, fill.side());
        assertEquals(new Quantity(10), fill.filledQuantity());
        assertEquals(new Price(new BigDecimal("70100")), fill.fillPrice());
    }

    @Test
    void 체결가가_없거나_0인_체결통보는_지정가로_근사해_Fill을_발행한다() {
        // 실측 체결 통보는 FID 910이 늘 있지만(2026-09-11), 빠지면 Fill.fillPrice(Price)를 만들 수 없다 —
        // 예전에는 null이 그대로 나가 PositionBook·DailyPnlTracker에서 NPE가 났다(조각 17).
        tradingService.onOrderRequest(order("key-1"));
        OrderEntity entity = stubEntity("key-1", Side.BUY, 10);

        handler.onOrderNotice(notice("체결", 4, null));
        handler.onOrderNotice(notice("체결", 10, BigDecimal.ZERO));

        assertEquals(2, published.size());
        assertEquals(new Price(new BigDecimal("70000")), ((Fill) published.get(0)).fillPrice());
        assertEquals(new Price(new BigDecimal("70000")), ((Fill) published.get(1)).fillPrice());
        assertEquals(new Quantity(6), ((Fill) published.get(1)).filledQuantity());
        assertEquals(10, entity.getFilledQuantity());
    }

    @Test
    void 매핑_없는_통보는_즉시_반영하지_않고_보류한다() {
        // tradingService.onOrderRequest를 호출하지 않았고, DB 폴백도 비어있으므로(mock 기본값)
        // 인메모리·DB 둘 다 매핑이 없다
        handler.onOrderNotice(notice("체결", 10, new BigDecimal("70100")));

        assertEquals(0, published.size());
    }

    @Test
    void 주문번호_저장보다_먼저_온_체결통보는_등록_후_재처리되어_Fill이_발행된다() {
        // R4: REST 주문 응답(주문번호 저장)보다 WS 체결 통보가 먼저 도착 — 전량 즉시 체결이면 뒤따르는 통보가 없다
        handler.onOrderNotice(notice("체결", 10, new BigDecimal("70100"), 0));
        handler.replayPendingNotices();
        assertEquals(0, published.size()); // 아직 미등록 → 계속 보류

        OrderEntity entity = stubEntity("key-late", Side.BUY, 10); // 주문번호 저장 완료
        handler.replayPendingNotices();

        assertEquals(1, published.size());
        assertEquals(new Quantity(10), ((Fill) published.get(0)).filledQuantity());
        assertEquals(OrderStatus.FILLED, entity.getStatus());

        handler.replayPendingNotices();
        assertEquals(1, published.size()); // 한 번 재처리한 통보는 다시 나오지 않는다
    }

    @Test
    void 보류_시간이_지나도_등록되지_않은_체결통보는_버린다() {
        handler.onOrderNotice(notice("체결", 10, new BigDecimal("70100")));
        clock.advance(PendingOrderNotices.HOLD.plusSeconds(1));
        handler.replayPendingNotices(); // 만료 → 버림(수동 주문 등)

        stubEntity("key-too-late", Side.BUY, 10);
        handler.replayPendingNotices();

        assertEquals(0, published.size());
    }

    @Test
    void 체결_아닌_상태_통보는_무시() {
        tradingService.onOrderRequest(order("key-1"));

        handler.onOrderNotice(notice("접수", 0, null));

        assertEquals(0, published.size());
    }

    @Test
    void 부분체결_2회_수신시_Fill_2회_발행() {
        // 실측 확정 2026-09-11: FID 911은 누적 체결량, 910은 누적 평균단가다(19주가
        // 1→3→19 누적으로 왔던 실측). 통보는 누적으로 오고 Fill은 증분으로 발행돼야 한다.
        tradingService.onOrderRequest(order("key-2"));
        stubEntity("key-2", Side.BUY, 10);

        handler.onOrderNotice(notice("체결", 4, new BigDecimal("70000")));          // 누적 4
        handler.onOrderNotice(notice("체결", 10, new BigDecimal("70030")));         // 누적 10 (평균가)

        assertEquals(2, published.size());
        Fill first = (Fill) published.get(0);
        Fill second = (Fill) published.get(1);
        assertEquals(new Quantity(4), first.filledQuantity());                                    // 증분 4
        assertEquals(new Quantity(6), second.filledQuantity());                                   // 증분 10-4=6
        assertEquals(new Price(new BigDecimal("70000")), first.fillPrice());                   // 첫 체결 = 평균가 그대로
        // 증분 단가 역산: (10×70030 − 4×70000) / 6 = 70050
        assertEquals(new Price(new BigDecimal("70050")), second.fillPrice());
        // 두 통보 모두 같은 원 주문(key-2)에 연결돼야 한다
        assertEquals("key-2", first.orderIdempotencyKey());
        assertEquals("key-2", second.orderIdempotencyKey());
    }

    @Test
    void 누적_체결량이_기존_이하인_중복_통보는_무시() {
        // 이중계상 결함(운영 1일차 ⑥)의 회귀 방지: 같은 누적치가 다시 오면 delta=0 → 무시.
        tradingService.onOrderRequest(order("key-dup"));
        stubEntity("key-dup", Side.BUY, 10);

        handler.onOrderNotice(notice("체결", 4, new BigDecimal("70000")));
        handler.onOrderNotice(notice("체결", 4, new BigDecimal("70000")));          // 중복

        assertEquals(1, published.size());
    }

    @Test
    void 인메모리_매핑_유실시_DB_폴백으로_체결통보_처리() {
        // 재시작 시나리오 시뮬레이션: tradingService.onOrderRequest를 호출하지 않아
        // 인메모리 맵은 비어있지만, DB에는 SUBMITTED 상태 주문이 남아있다고 가정한다.
        OrderEntity entity = new OrderEntity("20260813-BREAKOUT-005930-BUY-001", new StockCode("005930"), Side.SELL,
                new Quantity(10), new Price(new BigDecimal("70000")), "BREAKOUT");
        entity.transitionTo(OrderStatus.VALIDATED);
        entity.transitionTo(OrderStatus.SUBMITTING);
        entity.markSubmitted(new BrokerOrderId(BROKER_ORDER_ID));
        when(orderRepository.findByBrokerOrderId(new BrokerOrderId(BROKER_ORDER_ID))).thenReturn(Optional.of(entity));

        handler.onOrderNotice(notice("체결", 10, new BigDecimal("70100")));

        assertEquals(1, published.size());
        Fill fill = (Fill) published.get(0);
        assertEquals("20260813-BREAKOUT-005930-BUY-001", fill.orderIdempotencyKey());
        assertEquals(Side.SELL, fill.side()); // DB 엔티티의 side를 물려받는다
        assertEquals(OrderStatus.FILLED, entity.getStatus()); // applyFill로 상태도 갱신됐다
    }

    @Test
    void 접수_통보_수신시_ACCEPTED로_전이하고_Fill은_발행안함() {
        // 실측 확정 2026-09-11(FID 913="접수"): 체결이 아니므로 Fill은 발행되지 않고
        // OrderEntity 상태만 ACCEPTED로 바뀐다.
        OrderEntity entity = new OrderEntity("key-accept", new StockCode("005930"), Side.BUY,
                new Quantity(1), new Price(new BigDecimal("258000")), "test-strategy");
        entity.transitionTo(OrderStatus.VALIDATED);
        entity.transitionTo(OrderStatus.SUBMITTING);
        entity.markSubmitted(new BrokerOrderId(BROKER_ORDER_ID));
        when(orderRepository.findByBrokerOrderId(new BrokerOrderId(BROKER_ORDER_ID))).thenReturn(Optional.of(entity));

        handler.onOrderNotice(notice("접수", 0, null, 1));

        assertEquals(0, published.size());
        assertEquals(OrderStatus.ACCEPTED, entity.getStatus());
    }

    @Test
    void 미실측_상태_통보는_상태기계를_건드리지_않고_무시() {
        // "취소"/"거부"는 이번 실측(2026-09-11)에서 관측되지 않은 상태 문자열이다(TODO 실측).
        // 상태기계를 오염시키지 않도록 아무 전이도 없이 무시해야 한다.
        OrderEntity entity = new OrderEntity("key-unknown", new StockCode("005930"), Side.BUY,
                new Quantity(1), new Price(new BigDecimal("258000")), "test-strategy");
        entity.transitionTo(OrderStatus.VALIDATED);
        entity.transitionTo(OrderStatus.SUBMITTING);
        entity.markSubmitted(new BrokerOrderId(BROKER_ORDER_ID));
        when(orderRepository.findByBrokerOrderId(new BrokerOrderId(BROKER_ORDER_ID))).thenReturn(Optional.of(entity));

        handler.onOrderNotice(notice("취소", 0, null));

        assertEquals(0, published.size());
        assertEquals(OrderStatus.SUBMITTED, entity.getStatus()); // 손대지 않았다
    }

    @Test
    void 미체결잔량_0_통보시_로컬계산과_달라도_FILLED로_확정() {
        // 실측 확정 2026-09-11(FID 902): 브로커가 "미체결 0"을 통보하면, 로컬 주문수량
        // 기준 계산(applyFill)이 아직 PARTIALLY_FILLED라고 판단하더라도 902를 우선해 FILLED로
        // 확정한다. quantity=10인데 이번 통보 filledQuantity=3만 반영해 로컬 계산상으로는
        // PARTIALLY_FILLED가 나오는 상황을 의도적으로 만든다.
        OrderEntity entity = new OrderEntity("key-mismatch", new StockCode("005930"), Side.BUY,
                new Quantity(10), new Price(new BigDecimal("70000")), "test-strategy");
        entity.transitionTo(OrderStatus.VALIDATED);
        entity.transitionTo(OrderStatus.SUBMITTING);
        entity.markSubmitted(new BrokerOrderId(BROKER_ORDER_ID));
        when(orderRepository.findByBrokerOrderId(new BrokerOrderId(BROKER_ORDER_ID))).thenReturn(Optional.of(entity));

        handler.onOrderNotice(notice("체결", 3, new BigDecimal("70000"), 0));

        assertEquals(1, published.size());
        assertEquals(OrderStatus.FILLED, entity.getStatus());
    }

    @Test
    void 체결_저장이_동시_갱신과_충돌하면_최신_누적치로_증분을_다시_계산한다() {
        // 통보 처리 중 다른 경로가 먼저 4주를 반영해 저장했다 — 옛 사본(누적 0)으로 저장하면 충돌(R2).
        OrderEntity stale = new OrderEntity("key-conflict", new StockCode("005930"), Side.BUY,
                new Quantity(10), new Price(new BigDecimal("70000")), "test-strategy");
        stale.transitionTo(OrderStatus.VALIDATED);
        stale.transitionTo(OrderStatus.SUBMITTING);
        stale.markSubmitted(new BrokerOrderId(BROKER_ORDER_ID));
        OrderEntity latest = new OrderEntity("key-conflict", new StockCode("005930"), Side.BUY,
                new Quantity(10), new Price(new BigDecimal("70000")), "test-strategy");
        latest.transitionTo(OrderStatus.VALIDATED);
        latest.transitionTo(OrderStatus.SUBMITTING);
        latest.markSubmitted(new BrokerOrderId(BROKER_ORDER_ID));
        latest.applyFill(new Quantity(4));
        when(orderRepository.findByBrokerOrderId(new BrokerOrderId(BROKER_ORDER_ID)))
                .thenReturn(Optional.of(stale), Optional.of(latest));
        doThrow(new ObjectOptimisticLockingFailureException(OrderEntity.class, 1L))
                .when(orderRepository).save(stale);

        handler.onOrderNotice(notice("체결", 10, new BigDecimal("70000")));

        assertEquals(1, published.size());
        assertEquals(new Quantity(6), ((Fill) published.get(0)).filledQuantity()); // 10 − 최신 누적 4
        assertEquals(10, latest.getFilledQuantity());
        assertEquals(OrderStatus.FILLED, latest.getStatus());
    }

    // ── 실측 전문 재생 (docs/measured/ws_probe_20260911_intraday.txt, 마스킹 없음) ──
    // RealMessageParser는 market 패키지 전용(package-private)이라 이 테스트에서 직접 호출할
    // 수 없으므로, 실측 전문에서 확인한 FID 값을 그대로 옮겨 OrderNotice를 구성한다.

    @Test
    void 실측_전문_재생_매수_접수_후_체결() {
        OrderEntity entity = new OrderEntity("key-buy-replay", new StockCode("005930"), Side.BUY,
                new Quantity(1), new Price(new BigDecimal("258000")), "test-strategy");
        entity.transitionTo(OrderStatus.VALIDATED);
        entity.transitionTo(OrderStatus.SUBMITTING);
        entity.markSubmitted(new BrokerOrderId("0119433")); // 실측 브로커 주문번호(FID 9203)
        when(orderRepository.findByBrokerOrderId(new BrokerOrderId("0119433"))).thenReturn(Optional.of(entity));
        OrderNotice accepted = new OrderNotice(new BrokerOrderId("0119433"), new StockCode("005930"), "접수", 0, null,
                1, "00", Instant.now());
        OrderNotice filled = new OrderNotice(new BrokerOrderId("0119433"), new StockCode("005930"), "체결", 1,
                new Price(new BigDecimal("258000")), 0, "00", Instant.now());

        handler.onOrderNotice(accepted);
        assertEquals(OrderStatus.ACCEPTED, entity.getStatus());
        assertEquals(0, published.size());

        handler.onOrderNotice(filled);
        assertEquals(OrderStatus.FILLED, entity.getStatus());
        assertEquals(1, published.size());
        Fill fill = (Fill) published.get(0);
        assertEquals(new Quantity(1), fill.filledQuantity());
        assertEquals(new Price(new BigDecimal("258000")), fill.fillPrice());
    }

    @Test
    void 실측_전문_재생_매도_접수_후_체결() {
        OrderEntity entity = new OrderEntity("key-sell-replay", new StockCode("005930"), Side.SELL,
                new Quantity(1), new Price(new BigDecimal("258000")), "test-strategy");
        entity.transitionTo(OrderStatus.VALIDATED);
        entity.transitionTo(OrderStatus.SUBMITTING);
        entity.markSubmitted(new BrokerOrderId("0119574")); // 실측 브로커 주문번호(FID 9203)
        when(orderRepository.findByBrokerOrderId(new BrokerOrderId("0119574"))).thenReturn(Optional.of(entity));
        OrderNotice accepted = new OrderNotice(new BrokerOrderId("0119574"), new StockCode("005930"), "접수", 0, null,
                1, "00", Instant.now());
        OrderNotice filled = new OrderNotice(new BrokerOrderId("0119574"), new StockCode("005930"), "체결", 1,
                new Price(new BigDecimal("258000")), 0, "00", Instant.now());

        handler.onOrderNotice(accepted);
        assertEquals(OrderStatus.ACCEPTED, entity.getStatus());

        handler.onOrderNotice(filled);
        assertEquals(OrderStatus.FILLED, entity.getStatus());
        assertEquals(1, published.size());
        Fill fill = (Fill) published.get(0);
        assertEquals(Side.SELL, fill.side());
        assertEquals(new Quantity(1), fill.filledQuantity());
        assertEquals(new Price(new BigDecimal("258000")), fill.fillPrice());
    }
}

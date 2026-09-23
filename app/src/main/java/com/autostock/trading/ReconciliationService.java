package com.autostock.trading;

import com.autostock.execution.BrokerOutstandingOrder;
import com.autostock.execution.BrokerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reconciliation(대사) — 브로커가 Source of Truth라는 원칙의 구현체 (ARCHITECTURE.md 8절).
 *
 * <p>내부 DB(orders 테이블)만 믿지 않는다. 특히 {@link OrderStatus#UNKNOWN}은 "결과를
 * 모르는" 상태이므로, 이 서비스가 브로커의 미체결 목록과 대사(matching)해 실제 상태로
 * 확정시켜야 한다. SUBMITTED 상태 주문도 함께 대사 대상에 포함한다 — 앱 재시작 등으로
 * 인메모리 매핑이 유실됐을 수 있기 때문이다.
 *
 * <p>실행 시점(ARCHITECTURE.md 8절): 앱 시작({@link ApplicationReadyEvent}) 1회,
 * 이후 5분 주기({@link Scheduled}), 그리고 주문 전송 타임아웃 발생 시
 * {@link #requestReconcile(String)}로 즉시 단건 대사. LIVE 모드에서만 동작한다 — SIM은
 * 브로커가 없으므로 대사할 대상 자체가 없다.
 */
@Component
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final OrderRepository orderRepository;
    private final BrokerPort brokerPort;
    private final TradingProperties properties;

    public ReconciliationService(OrderRepository orderRepository, BrokerPort brokerPort,
                                 TradingProperties properties) {
        this.orderRepository = orderRepository;
        this.brokerPort = brokerPort;
        this.properties = properties;
    }

    /**
     * 직전 전체 대사 시각 — 짧은 간격 중복 실행 가드.
     * 실측(2026-09-11 운영 로그): 기동 시 onStartup(ApplicationReady)과 auto-start 경로의
     * start()가 거의 동시에 reconcile()을 불러 ka10075가 1초에 2회 나갔고, 키움 TR 제한
     * (return_code 5, "허용된 요청 개수 초과 1700, 잔량=1")에 걸렸다.
     */
    private volatile long lastFullReconcileMs = 0L;
    private static final long MIN_INTERVAL_MS = 10_000L;

    /**
     * 앱 기동 직후 1회 전체 대사 — 재시작 전 인메모리 상태가 유실된 주문을 회복한다.
     *
     * <p><b>실패해도 앱은 죽지 않는다 (실측 2026-09-11)</b>: 이 리스너에서 예외가 새어나가면
     * SpringApplication.run까지 전파되어 "Application run failed"로 앱 전체가 종료된다 —
     * 무인 운영에서 브로커 일시 오류(429 등) = 앱 사망이 되므로 여기서 반드시 삼킨다.
     * 대사는 5분 주기 스케줄이 다음 회차에 다시 시도한다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (properties.mode() == TradingProperties.Mode.LIVE) {
            reconcileSafely("기동 시");
        }
    }

    /**
     * 주기적 전체 대사(5분). 실패는 경고 후 다음 회차 재시도 — 스케줄러를 죽이지 않는다.
     * fixedDelay인 이유는 {@code StaleOrderCanceller} 참고(절전 후 밀린 회차 동시 실행 방지).
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void scheduledReconcile() {
        if (properties.mode() == TradingProperties.Mode.LIVE) {
            reconcileSafely("주기");
        }
    }

    private void reconcileSafely(String trigger) {
        try {
            reconcile();
        } catch (Exception e) {
            log.warn("{} 대사 실패 — 다음 주기(5분)에 재시도. 브로커 일시 오류로 앱을 죽이지 않는다", trigger, e);
        }
    }

    /**
     * DB의 UNKNOWN·SUBMITTED 주문 전체를 브로커 미체결 목록과 대사한다.
     * 대상이 없으면 브로커 호출 자체를 생략한다(불필요한 API 호출 방지).
     * 직전 실행 후 10초 이내 재호출은 스킵한다(기동 시 이중 호출 가드 — 필드 Javadoc 참고).
     */
    public void reconcile() {
        long now = System.currentTimeMillis();
        if (now - lastFullReconcileMs < MIN_INTERVAL_MS) {
            log.debug("직전 대사 후 {}ms — 중복 실행 스킵", now - lastFullReconcileMs);
            return;
        }
        lastFullReconcileMs = now;
        List<OrderEntity> pending = orderRepository.findByStatusIn(
                List.of(OrderStatus.UNKNOWN, OrderStatus.SUBMITTED));
        if (pending.isEmpty()) {
            return;
        }
        Map<String, BrokerOutstandingOrder> outstandingByBrokerOrderId = brokerPort.outstandingOrders()
                .stream()
                .collect(Collectors.toMap(BrokerOutstandingOrder::brokerOrderId, Function.identity(),
                        (a, b) -> a)); // 중복 브로커주문번호는 이론상 없어야 하지만 방어적으로 첫 값 유지

        for (OrderEntity order : pending) {
            reconcileOne(order, outstandingByBrokerOrderId);
        }
    }

    /** 단건 대사 — 주문 전송 타임아웃(UNKNOWN 전이) 직후 TradingService가 즉시 호출한다. */
    public void requestReconcile(String clientOrderId) {
        Optional<OrderEntity> order = orderRepository.findByClientOrderId(clientOrderId);
        if (order.isEmpty()) {
            log.warn("reconcile 요청 대상 주문을 찾을 수 없음: {}", clientOrderId);
            return;
        }
        Map<String, BrokerOutstandingOrder> outstandingByBrokerOrderId = brokerPort.outstandingOrders()
                .stream()
                .collect(Collectors.toMap(BrokerOutstandingOrder::brokerOrderId, Function.identity(), (a, b) -> a));
        reconcileOne(order.get(), outstandingByBrokerOrderId);
    }

    /**
     * 주문 1건을 브로커 미체결 목록과 대사한다. 세 가지 경우:
     * <ol>
     *   <li>브로커에 존재 → 최소한 접수는 됐다는 뜻이므로 SUBMITTED로 유지/확정</li>
     *   <li>브로커에 없고 체결로 추정됨 → 체결 수량 확정에는 체결내역 조회 TR이 필요하다
     *       (TODO 실측 — 아직 구현하지 않고 자리만 마련해 둔다, {@link #probeFillStatus(OrderEntity)})</li>
     *   <li>어느 쪽도 판단할 수 없음 → REJECTED로 자동 확정하지 않는다. 실제로는 체결됐는데
     *       REJECTED로 잘못 단정하면 포지션 추적이 깨지는 사고가 더 위험하다고 판단했다
     *       (정책 결정, ARCHITECTURE.md 8절 "Broker가 Source of Truth" 원칙 — 확실하지
     *       않으면 UNKNOWN을 유지하고 운영자가 수동 확인하도록 경고만 남긴다).</li>
     * </ol>
     */
    private void reconcileOne(OrderEntity order, Map<String, BrokerOutstandingOrder> outstandingByBrokerOrderId) {
        if (order.getBrokerOrderId() == null) {
            // SUBMITTING 단계에서 UNKNOWN이 된 경우 — 브로커 주문번호 자체가 없어 미체결
            // 목록으로는 식별 불가능하다. 당일 주문내역 조회 TR로 clientOrderId를 매칭해야
            // 하지만 해당 TR 연동은 TODO 실측 — 지금은 경고만 남긴다.
            log.warn("brokerOrderId 없는 UNKNOWN 주문 — 주문내역 조회 TR 필요(TODO 실측): {}",
                    order.getClientOrderId());
            return;
        }

        BrokerOutstandingOrder found = outstandingByBrokerOrderId.get(order.getBrokerOrderId());
        if (found != null) {
            if (order.getStatus() == OrderStatus.UNKNOWN) {
                order.transitionTo(OrderStatus.SUBMITTED);
                orderRepository.save(order);
                log.info("Reconciliation: UNKNOWN → SUBMITTED 확정(브로커 미체결 목록에서 발견): {}",
                        order.getClientOrderId());
            }
            return; // 이미 SUBMITTED면 상태 변화 없음
        }

        // 브로커 미체결 목록에 없다 — 체결 완료돼 목록에서 빠졌을 수도, 애초에 거부됐을 수도 있다.
        Optional<Boolean> filled = probeFillStatus(order);
        if (filled.isPresent() && filled.get()) {
            log.info("Reconciliation: 브로커 체결내역에서 확인됨(TODO 실측 TR 구현 후 활성화): {}",
                    order.getClientOrderId());
            // TODO 실측: 체결내역 조회 TR 응답으로 filledQuantity/fillPrice를 받아
            // order.applyFill(...)을 호출하고 Fill 이벤트도 재발행해야 한다.
        } else if (filled.isPresent()) {
            // 명시적으로 "체결 아님"이 확인된 경우에만 REJECTED로 확정한다(정책).
            if (order.getStatus().canTransitionTo(OrderStatus.REJECTED)) {
                order.transitionTo(OrderStatus.REJECTED);
                orderRepository.save(order);
                log.warn("Reconciliation: 브로커 미체결/체결 어디에도 없음 — REJECTED로 확정: {}",
                        order.getClientOrderId());
            }
        } else {
            log.warn("Reconciliation: 브로커 미체결 목록에 없음 — 체결/거부 여부 확인 불가(TODO 실측 "
                    + "체결내역 조회 TR 필요), UNKNOWN 유지·수동 확인 필요: {}", order.getClientOrderId());
        }
    }

    /**
     * 체결내역 조회 TR로 실제 체결 여부를 확인한다.
     * <p><b>TODO 실측</b>: 키움 REST의 계좌별 체결내역 조회 TR(api-id 미확정)을 연동해야 한다.
     * 지금은 항상 {@link Optional#empty()}를 돌려주는 스텁이다 — "확인 불가" 취급으로
     * 안전 측(자동 REJECTED 단정 금지)에 서게 된다.
     */
    private Optional<Boolean> probeFillStatus(OrderEntity order) {
        return Optional.empty();
    }
}

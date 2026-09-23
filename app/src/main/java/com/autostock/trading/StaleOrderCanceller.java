package com.autostock.trading;

import com.autostock.execution.BrokerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 미체결 주문 타임아웃 자동 취소 — 1분 주기로 SUBMITTED 상태가 너무 오래(기본 5분,
 * {@code execution.stale-order-timeout}) 지속된 주문을 취소 요청한다.
 *
 * <p>존재 이유: 브로커가 접수(SUBMITTED)만 하고 체결통보가 영영 오지 않는 경우(시장가가
 * 안 맞거나, WS 통보가 유실되는 등) 미체결 주문이 계속 살아남아 리스크 한도(동시 보유
 * 종목 수 등)를 잠식할 수 있다. 이 클래스가 오래된 주문을 정리한다.
 *
 * <p>시간 판단은 {@link Clock}을 주입받아 사용한다 — 테스트에서 {@link Clock#fixed}로
 * 고정해 "지금이 몇 분 지났다"를 결정론적으로 검증할 수 있게 하기 위해서다
 * (System.currentTimeMillis()를 직접 쓰면 테스트가 실제 시간 경과에 의존하게 된다).
 *
 * <p>LIVE 모드에서만 동작한다 — SIM은 즉시 체결이라 미체결 상태가 존재하지 않는다.
 */
@Component
public class StaleOrderCanceller {

    private static final Logger log = LoggerFactory.getLogger(StaleOrderCanceller.class);

    private final OrderRepository orderRepository;
    private final BrokerPort brokerPort;
    private final TradingProperties properties;
    private final Clock clock;

    public StaleOrderCanceller(OrderRepository orderRepository, BrokerPort brokerPort,
                               TradingProperties properties, Clock clock) {
        this.orderRepository = orderRepository;
        this.brokerPort = brokerPort;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 1분 주기 점검. {@code fixedDelay}(직전 실행 종료 기준)를 쓴다 — 가상 스레드 스케줄러
     * (SimpleAsyncTaskScheduler)는 fixedRate의 밀린 회차를 <b>동시에</b> 실행하므로, PC 절전 후
     * 깨어나면 수십~수백 회차가 한꺼번에 DB 커넥션 10개를 두고 경쟁해 풀이 고갈됐다
     * (2026-09-22 실측: active=10, waiting=210, 215회 실패). fixedDelay는 밀린 회차를 1회로 합친다.
     */
    @Scheduled(fixedDelay = 60_000)
    public void cancelStaleOrders() {
        if (properties.mode() != TradingProperties.Mode.LIVE) {
            return;
        }
        Instant threshold = Instant.now(clock).minus(properties.staleOrderTimeout());
        List<OrderEntity> submitted = orderRepository.findByStatusIn(List.of(OrderStatus.SUBMITTED));
        for (OrderEntity order : submitted) {
            if (order.getUpdatedAt().isBefore(threshold)) {
                cancelOne(order);
            }
        }
    }

    private void cancelOne(OrderEntity order) {
        try {
            order.transitionTo(OrderStatus.CANCEL_REQUESTED);
            orderRepository.save(order);
            long remaining = order.getQuantity() - order.getFilledQuantity();
            brokerPort.cancelOrder(order.getBrokerOrderId(), order.getSymbol(), remaining);
            log.info("미체결 타임아웃 취소 요청: clientOrderId={} brokerOrderId={} 경과 상태 갱신 시각={}",
                    order.getClientOrderId(), order.getBrokerOrderId(), order.getUpdatedAt());
        } catch (Exception e) {
            // 취소 요청 자체가 실패해도(네트워크 오류 등) 다음 주기에 재시도된다 —
            // 단, 상태는 이미 CANCEL_REQUESTED로 바뀌어 저장됐으므로 재시도 시
            // findByStatusIn(SUBMITTED) 대상에서는 빠진다. 이 경우는 Reconciliation이
            // 나중에 CANCEL_REQUESTED 상태를 대사해 확정지어야 한다(TODO 실측: 현재
            // ReconciliationService는 UNKNOWN/SUBMITTED만 대사 대상으로 삼는다 — 향후
            // CANCEL_REQUESTED 장기 체류 감지도 추가 검토).
            log.error("미체결 취소 요청 실패: clientOrderId={}", order.getClientOrderId(), e);
        }
    }
}

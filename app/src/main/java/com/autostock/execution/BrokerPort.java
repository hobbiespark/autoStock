package com.autostock.execution;

import com.autostock.common.event.OrderRequest;

import java.util.List;

/**
 * 브로커 포트 — execution/trading이 아는 것은 이 인터페이스뿐이다(ARCHITECTURE.md 4절,
 * Hexagonal Port/Adapter). 키움 REST 응답 Map/DTO는 이 경계 밖으로 절대 노출하지 않는다.
 *
 * <p>{@link KiwoomBrokerAdapter}가 현재 유일한 구현체다. 다른 브로커로 교체하거나
 * 테스트에서 가짜 브로커로 바꿔치기할 때 이 인터페이스만 다시 구현하면 된다 —
 * ExecutionService/ReconciliationService/StaleOrderCanceller는 이 인터페이스만 의존하고
 * KiwoomRestClient·TrId 등 키움 구체 타입은 전혀 모른다.
 *
 * <p>SIM 모드는 이 포트를 거치지 않는다(ExecutionService 참고) — 브로커 없이
 * 이벤트 루프만 검증하는 모드이기 때문이다.
 */
public interface BrokerPort {

    /** 신규 주문을 브로커에 제출한다. */
    BrokerOrderResult placeOrder(OrderRequest request);

    /**
     * 미체결 주문을 취소 요청한다.
     *
     * @param brokerOrderId 취소할 브로커 주문번호
     * @param symbol        종목코드(취소 TR 파라미터로 필요)
     * @param quantity      취소 수량(전량 취소 정책이면 원 주문 잔량)
     */
    void cancelOrder(String brokerOrderId, String symbol, long quantity);

    /** 현재 미체결 주문 목록을 조회한다(ka10075) — Reconciliation의 대사 대상. */
    List<BrokerOutstandingOrder> outstandingOrders();

    /** 계좌 잔고를 조회한다(kt00018). */
    BrokerBalance balance();
}

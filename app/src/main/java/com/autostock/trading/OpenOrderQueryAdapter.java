package com.autostock.trading;

import com.autostock.common.event.Side;
import com.autostock.common.util.StockCode;
import com.autostock.risk.OpenOrderQuery;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * risk의 미체결 매수 조회 포트 구현 (실행 계획 1.2, BE-P1-2, 2026-10-02 — aiDoc/open-order-aware-risk.md).
 *
 * <p>"결과가 확정되지 않은 매수" = 계획의 5개 상태(SUBMITTING·SUBMITTED·ACCEPTED·PARTIALLY_FILLED·UNKNOWN)에
 * CANCEL_REQUESTED를 더한 6개다. 취소를 요청한 주문도 취소가 확인되기 전에 체결될 수 있다(전이표의
 * CANCEL_REQUESTED → FILLED·PARTIALLY_FILLED). CREATED·VALIDATED는 넣지 않는다 — 전송 전 단계라 브로커에 없고,
 * 저장 직후 앱이 죽어 남은 행이 그 종목 매수를 영영 막지 않게 하기 위해서다(계획과 같음).
 *
 * <p>의존 방향은 trading → risk(포트 구현)다. risk는 이 클래스도 주문 상태기계도 모른다 — {@code EquitySource}를
 * execution이 구현하는 것과 같은 모양({@code ArchitectureRulesTest}가 risk → trading 의존을 막는다).
 */
@Component
class OpenOrderQueryAdapter implements OpenOrderQuery {

    static final Set<OrderStatus> OPEN_STATUSES = EnumSet.of(
            OrderStatus.SUBMITTING, OrderStatus.SUBMITTED, OrderStatus.ACCEPTED,
            OrderStatus.PARTIALLY_FILLED, OrderStatus.CANCEL_REQUESTED, OrderStatus.UNKNOWN);

    private final OrderRepository orderRepository;

    OpenOrderQueryAdapter(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    public Set<StockCode> symbolsWithOpenBuy() {
        return orderRepository.findBySideAndStatusIn(Side.BUY, OPEN_STATUSES).stream()
                .map(OrderEntity::getSymbol)
                .collect(Collectors.toUnmodifiableSet());
    }
}

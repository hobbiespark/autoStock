package com.autostock.trading;

import com.autostock.common.event.Fill;
import com.autostock.common.event.OrderNotice;
import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * WS 주문체결통보(OrderNotice) → Fill 변환기.
 *
 * <p>LIVE 모드에서는 주문을 접수한다고 바로 체결되는 게 아니다. 실제 체결은
 * 키움 서버가 WS로 비동기 통보해 준다 (market 모듈이 OrderNotice로 정규화해
 * 발행). 이 클래스가 그 통보를 받아 "진짜 체결이 맞는지" 판단하고, 맞다면
 * PositionBook 등 나머지 시스템이 이해하는 공통 언어인 Fill로 바꿔 발행한다.
 * 동시에 {@link OrderEntity#applyFill(long)}로 주문 상태기계도 갱신한다.
 *
 * <p>처리 순서:
 * <pre>
 *   1. 체결 수량(filledQuantity)이 0 이하이거나 상태가 체결이 아니면 무시
 *      (접수/취소 등 체결 아닌 통보는 여기서 걸러진다)
 *   2. brokerOrderId로 원 주문요청을 조회 — 인메모리(TradingService) 1차,
 *      없으면 DB(OrderRepository) 2차 폴백(재시작으로 인메모리가 유실된 경우)
 *      - 둘 다 못 찾으면 경고 로그만 남기고 무시 (수동 주문 등 이 시스템이 모르는 주문)
 *   3. 찾으면 원 주문의 clientOrderId·side를 물려받아 Fill 발행 + OrderEntity.applyFill 반영
 * </pre>
 *
 * <p><b>부분체결 처리:</b> 같은 주문(brokerOrderId)에 대해 통보가 여러 번 올 수
 * 있다. 각 통보는 "이번에 새로 체결된 수량"만 담고 있다고 가정하므로(문서 기반
 * 추정, 실측 TODO) 통보 하나마다 Fill을 하나씩 발행한다 — 누적/중복 제거 로직은
 * 두지 않는다. PositionBook은 Fill을 여러 번 받아도 알아서 누적하도록 설계돼 있고,
 * OrderEntity.applyFill도 누적치를 스스로 관리한다.
 *
 * <p>TODO Phase 2 실측 확정: status 문자열의 정확한 값 집합(예: "체결"이 정확히
 * 이 글자인지, "완전체결"/"부분체결"처럼 접두어가 붙는지)은 scripts/ws_probe.py
 * 실측 후 확정해야 한다. 현재는 "체결"이라는 부분 문자열 포함 여부로 판단한다.
 */
@Component
public class OrderNoticeHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderNoticeHandler.class);

    /**
     * 체결을 의미하는 것으로 간주하는 상태 문자열 키워드.
     * TODO Phase 2 실측: 키움 913(주문상태) 필드의 정확한 값 집합 확인 후 갱신.
     */
    private static final String FILLED_KEYWORD = "체결";

    private final TradingService tradingService;
    private final OrderRepository orderRepository;
    private final ApplicationEventPublisher publisher;

    public OrderNoticeHandler(TradingService tradingService, OrderRepository orderRepository,
                              ApplicationEventPublisher publisher) {
        this.tradingService = tradingService;
        this.orderRepository = orderRepository;
        this.publisher = publisher;
    }

    @EventListener
    public void onOrderNotice(OrderNotice notice) {
        if (notice.filledQuantity() <= 0 || !isFilled(notice.status())) {
            // 접수/취소 등 체결이 아닌 통보 — Fill로 이어지지 않는다
            return;
        }

        // 1차: 인메모리(빠름). 2차: DB 폴백(재시작으로 인메모리 맵이 유실된 경우).
        OrderRequest original = tradingService.findByBrokerOrderId(notice.brokerOrderId());
        Optional<OrderEntity> entity = orderRepository.findByBrokerOrderId(notice.brokerOrderId());

        if (original == null && entity.isEmpty()) {
            // 이 시스템이 낸 주문이 아니거나(수동 주문 등), 둘 다 유실된 경우.
            // 조용히 버리면 사고 원인 추적이 안 되므로 반드시 경고로 남긴다.
            log.warn("brokerOrderId 매핑 없는 체결통보 무시 (수동 주문 등으로 추정): {}", notice.brokerOrderId());
            return;
        }

        String clientOrderId = original != null ? original.idempotencyKey() : entity.get().getClientOrderId();
        // 매수/매도 방향은 통보에 없으므로 원 주문(인메모리 우선, 없으면 DB 엔티티)에서 가져온다
        Side side = original != null ? original.side() : entity.get().getSide();

        entity.ifPresent(order -> {
            try {
                order.applyFill(notice.filledQuantity());
                orderRepository.save(order);
            } catch (IllegalStateException e) {
                // 상태기계상 이미 종결된 주문에 체결통보가 중복 도착한 경우 등 — Fill 발행 자체는
                // 계속 진행하되(PositionBook 등은 별개로 최신 상태를 반영해야 하므로), 원인 추적을
                // 위해 에러로 남긴다.
                log.error("체결통보 반영 중 주문 상태 갱신 실패(Fill은 계속 발행): {}", e.getMessage());
            }
        });

        publisher.publishEvent(new Fill(
                clientOrderId,
                notice.brokerOrderId(),
                notice.symbol(),
                side,
                notice.filledQuantity(),
                notice.fillPrice(),
                notice.timestamp()));
    }

    private boolean isFilled(String status) {
        return status != null && status.contains(FILLED_KEYWORD);
    }
}

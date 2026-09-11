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
 * 있다. 각 통보는 "이번에 새로 체결된 수량"만 담고 있다고 가정한다 — 이번 실측
 * (2026-09-11)은 수량 1주 단건 체결만 확인했을 뿐 실제 부분체결 시나리오는 관측하지
 * 못해 이 가정 자체는 여전히 TODO 실측이다. 통보 하나마다 Fill을 하나씩 발행한다 —
 * 누적/중복 제거 로직은 두지 않는다. PositionBook은 Fill을 여러 번 받아도 알아서
 * 누적하도록 설계돼 있고, OrderEntity.applyFill도 누적치를 스스로 관리한다.
 *
 * <p><b>실측 확정 2026-09-11</b> — docs/measured/ws_probe_20260911_intraday.txt로
 * 913(주문상태) 필드의 실제 값이 정확히 "접수"/"체결" 두 문자열임을 확인했다.
 * "접수"는 브로커/거래소가 주문을 정식으로 받아들였다는 통보로, OrderEntity를
 * {@link OrderStatus#ACCEPTED}로 전이만 시키고 Fill은 발행하지 않는다. "체결"은
 * 기존과 같이 Fill 발행 + applyFill로 이어진다. 그 외 상태 문자열("취소"/"거부"
 * 등 — 이번 실측에서는 관측되지 않았다, TODO 실측)은 상태기계를 오염시키지
 * 않도록 로그만 남기고 무시한다(방어적 처리).
 */
@Component
public class OrderNoticeHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderNoticeHandler.class);

    /**
     * 접수 통보 상태 문자열. 실측 확정 2026-09-11(FID 913 = "접수").
     */
    private static final String STATUS_ACCEPTED = "접수";

    /**
     * 체결을 의미하는 것으로 간주하는 상태 문자열 키워드.
     * 실측 확정 2026-09-11: 관측된 값은 정확히 "체결"이다. "부분체결"류 접두 변형은
     * 실측되지 않았으나(TODO 실측), 접두 변형이 오더라도 안전하게 체결로 처리되도록
     * contains 방식은 유지한다.
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
        if (STATUS_ACCEPTED.equals(notice.status())) {
            handleAccepted(notice);
            return;
        }

        if (notice.filledQuantity() <= 0 || !isFilled(notice.status())) {
            // 취소/거부 등 실측 미확정 상태(또는 체결수량 0) — 상태기계를 함부로 건드리지
            // 않도록 상태 전이 없이 로그만 남기고 무시한다(TODO 실측: "취소"/"거부" 등).
            if (notice.status() != null && !notice.status().isBlank()) {
                log.info("접수/체결 아닌 주문상태 통보 무시(TODO 실측 대상일 수 있음): status={}, brokerOrderId={}",
                        notice.status(), notice.brokerOrderId());
            }
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
                // 실측 확정 2026-09-11(FID 902): 미체결 잔량이 0으로 내려오면 브로커 기준
                // "완전 소진"이 확정된 것이다. 로컬 계산(applyFill)이 이미 FILLED로 판단했다면
                // 아무 일도 하지 않고, PARTIALLY_FILLED로 남아있다면(로컬 주문수량 정보와
                // 브로커 잔량이 어긋난 드문 경우) 902를 우선해 FILLED로 확정한다.
                // remainingQuantity == -1은 "필드 자체가 없던 통보"(과거 픽스처 등)를 뜻하므로
                // 건드리지 않는다.
                if (notice.remainingQuantity() == 0 && order.getStatus() != OrderStatus.FILLED) {
                    order.transitionTo(OrderStatus.FILLED);
                }
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

    /**
     * 실측 확정 2026-09-11(FID 913 = "접수"): 브로커/거래소가 주문을 정식으로
     * 접수했다는 통보. Fill로 이어지지 않으므로(체결수량 0) OrderEntity 상태만
     * ACCEPTED로 전이한다. 매핑되는 주문이 없으면(수동 주문 등) 조용히 무시한다 —
     * 체결과 달리 접수 누락은 계좌 정합성에 치명적이지 않아 warn까지는 아니다.
     */
    private void handleAccepted(OrderNotice notice) {
        orderRepository.findByBrokerOrderId(notice.brokerOrderId()).ifPresentOrElse(order -> {
            try {
                order.transitionTo(OrderStatus.ACCEPTED);
                orderRepository.save(order);
            } catch (IllegalStateException e) {
                // 이미 ACCEPTED를 지나 체결/취소 등으로 넘어간 뒤 접수 통보가 뒤늦게 도착한
                // 경우 등 — 상태기계를 거스르지 않고 원인만 로그로 남긴다.
                log.error("접수통보 반영 중 주문 상태 갱신 실패: {}", e.getMessage());
            }
        }, () -> log.debug("brokerOrderId 매핑 없는 접수통보 무시 (수동 주문 등으로 추정): {}", notice.brokerOrderId()));
    }

    private boolean isFilled(String status) {
        return status != null && status.contains(FILLED_KEYWORD);
    }
}

package com.autostock.execution;

import com.autostock.common.event.Fill;
import com.autostock.common.event.OrderNotice;
import com.autostock.common.event.OrderRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * WS 주문체결통보(OrderNotice) → Fill 변환기.
 *
 * <p>LIVE 모드에서는 주문을 접수한다고 바로 체결되는 게 아니다. 실제 체결은
 * 키움 서버가 WS로 비동기 통보해 준다 (marketdata 모듈이 OrderNotice로 정규화해
 * 발행). 이 클래스가 그 통보를 받아 "진짜 체결이 맞는지" 판단하고, 맞다면
 * PositionBook 등 나머지 시스템이 이해하는 공통 언어인 Fill로 바꿔 발행한다.
 *
 * <p>처리 순서:
 * <pre>
 *   1. 체결 수량(filledQuantity)이 0 이하이거나 상태가 체결이 아니면 무시
 *      (접수/취소 등 체결 아닌 통보는 여기서 걸러진다)
 *   2. brokerOrderId로 원 주문요청(ExecutionService의 맵)을 조회
 *      - 못 찾으면 경고 로그만 남기고 무시 (수동 주문 등 이 시스템이 모르는 주문)
 *   3. 찾으면 원 주문의 idempotencyKey·side를 그대로 물려받아 Fill 발행
 * </pre>
 *
 * <p><b>부분체결 처리:</b> 같은 주문(brokerOrderId)에 대해 통보가 여러 번 올 수
 * 있다. 각 통보는 "이번에 새로 체결된 수량"만 담고 있다고 가정하므로(문서 기반
 * 추정, 실측 TODO) 통보 하나마다 Fill을 하나씩 발행한다 — 누적/중복 제거 로직은
 * 두지 않는다. PositionBook은 Fill을 여러 번 받아도 알아서 누적하도록 설계돼 있다.
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

    private final ExecutionService executionService;
    private final ApplicationEventPublisher publisher;

    public OrderNoticeHandler(ExecutionService executionService, ApplicationEventPublisher publisher) {
        this.executionService = executionService;
        this.publisher = publisher;
    }

    @EventListener
    public void onOrderNotice(OrderNotice notice) {
        if (notice.filledQuantity() <= 0 || !isFilled(notice.status())) {
            // 접수/취소 등 체결이 아닌 통보 — Fill로 이어지지 않는다
            return;
        }

        OrderRequest original = executionService.findByBrokerOrderId(notice.brokerOrderId());
        if (original == null) {
            // 이 시스템이 낸 주문이 아니거나(수동 주문 등), 재시작으로 맵이 유실된 경우.
            // 조용히 버리면 사고 원인 추적이 안 되므로 반드시 경고로 남긴다.
            log.warn("brokerOrderId 매핑 없는 체결통보 무시 (수동 주문 등으로 추정): {}", notice.brokerOrderId());
            return;
        }

        publisher.publishEvent(new Fill(
                original.idempotencyKey(),   // 원 주문의 멱등키를 그대로 물려받아 PositionBook 등이 연결지을 수 있게 함
                notice.brokerOrderId(),
                notice.symbol(),
                original.side(),             // 매수/매도 방향은 통보에 없으므로 원 주문에서 가져온다
                notice.filledQuantity(),
                notice.fillPrice(),
                notice.timestamp()));
    }

    private boolean isFilled(String status) {
        return status != null && status.contains(FILLED_KEYWORD);
    }
}

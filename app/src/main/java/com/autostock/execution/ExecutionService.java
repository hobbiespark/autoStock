package com.autostock.execution;

import com.autostock.common.event.OrderRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 주문 실행 골격. Phase 2에서 키움 주문 API 연동 + Fill 발행.
 * 멱등키 중복은 즉시 거부 (PLAN 8절 이상 감지).
 */
@Component
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    private final Set<String> seenIdempotencyKeys = ConcurrentHashMap.newKeySet();

    @EventListener
    public void onOrderRequest(OrderRequest request) {
        if (!seenIdempotencyKeys.add(request.idempotencyKey())) {
            log.warn("중복 주문 거부 (멱등키 재사용): {}", request.idempotencyKey());
            return;
        }
        // TODO Phase 2: TrId.ORDER_BUY/ORDER_SELL 호출, 주문번호 추적, 체결 시 Fill 발행
        log.info("주문 요청 수신 (실행 미구현 골격): {} {} {}주",
                request.symbol(), request.side(), request.quantity());
    }
}

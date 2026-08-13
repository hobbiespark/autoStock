package com.autostock.execution;

import com.autostock.common.event.Fill;
import com.autostock.common.event.OrderRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 주문 실행: 멱등키 중복 거부 후 모드에 따라 처리.
 * SIM: 지정가 즉시 전량 체결로 Fill 발행 — 이벤트 루프 검증용.
 * LIVE: 키움 주문 API 호출. 체결 확인은 WS 체결통보 연동 시 대체 (현재는 주문 접수만).
 */
@Component
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    private final Set<String> seenIdempotencyKeys = ConcurrentHashMap.newKeySet();
    private final AtomicLong simOrderSeq = new AtomicLong(0);

    private final ExecutionProperties properties;
    private final KiwoomOrderService orderService;
    private final ApplicationEventPublisher publisher;

    public ExecutionService(ExecutionProperties properties,
                            KiwoomOrderService orderService,
                            ApplicationEventPublisher publisher) {
        this.properties = properties;
        this.orderService = orderService;
        this.publisher = publisher;
    }

    @EventListener
    public void onOrderRequest(OrderRequest request) {
        if (!seenIdempotencyKeys.add(request.idempotencyKey())) {
            log.warn("중복 주문 거부 (멱등키 재사용): {}", request.idempotencyKey());
            return;
        }
        switch (properties.mode()) {
            case SIM -> executeSim(request);
            case LIVE -> executeLive(request);
        }
    }

    private void executeSim(OrderRequest request) {
        String brokerOrderId = "SIM-" + simOrderSeq.incrementAndGet();
        log.info("[SIM] 즉시 체결: {} {} {}주 @ {}",
                request.symbol(), request.side(), request.quantity(), request.limitPrice());
        publisher.publishEvent(new Fill(
                request.idempotencyKey(),
                brokerOrderId,
                request.symbol(),
                request.side(),
                request.quantity(),
                request.limitPrice(),
                Instant.now()));
    }

    private void executeLive(OrderRequest request) {
        String brokerOrderId = orderService.placeOrder(request);
        log.info("[LIVE] 주문 접수: {} → 주문번호 {}", request.symbol(), brokerOrderId);
        // TODO Phase 2 후반: WS 체결통보 수신 → Fill 발행, 미체결 타임아웃 취소
    }
}

package com.autostock.execution;

import com.autostock.common.event.Fill;
import com.autostock.common.event.OrderRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 주문 실행 서비스 — OrderRequest를 받아 실제(또는 가상) 주문으로 바꾼다.
 *
 * <p>두 가지 실행 모드 (application.yml의 execution.mode):
 * <pre>
 *   SIM  : 브로커 없이 지정가 즉시 전량 체결로 간주하고 Fill 발행.
 *          앱키가 없어도 "시그널→리스크→주문→체결→포지션 갱신" 전체 루프를
 *          돌려볼 수 있게 해주는 검증용 모드. (현재 기본값)
 *   LIVE : 키움 주문 API 실제 호출. 체결 확인은 WS 체결통보로 별도 수신.
 * </pre>
 *
 * <p><b>멱등성(idempotency)이 이 클래스의 첫 번째 책임이다.</b>
 * 이벤트 재전송, 리스너 중복 등록, 재시도 버그 — 어떤 이유로든 같은 주문 요청이
 * 두 번 도착할 수 있다. 같은 멱등키를 두 번 실행하면 돈이 두 번 나간다.
 * 그래서 처리한 키를 기억해 두고 재수신은 조용히 버린다.
 */
@Component
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    /**
     * 이미 처리한 멱등키 집합.
     * ConcurrentHashMap 기반 Set이라 add()가 원자적 — "동시에 두 스레드가 같은 키를
     * 추가하면 한쪽만 true"가 보장된다. 이것이 중복 실행 방지의 핵심.
     * TODO Phase 2 후반: 재시작 후에도 유지되도록 DB(orders 테이블)로 이전.
     */
    private final Set<String> seenIdempotencyKeys = ConcurrentHashMap.newKeySet();

    /** SIM 모드 가상 주문번호 시퀀스 (SIM-1, SIM-2, ...) */
    private final AtomicLong simOrderSeq = new AtomicLong(0);

    /**
     * LIVE 모드 전용: 브로커 주문번호 → 원 주문요청 맵.
     * WS 체결통보(OrderNotice)는 brokerOrderId만 들고 오기 때문에, 이걸 원래
     * OrderRequest(멱등키·수량·매수매도 방향 등)로 되돌려 찾을 방법이 있어야
     * Fill을 만들 수 있다. {@link OrderNoticeHandler}가 이 맵을 조회한다.
     * TODO Phase 2 후반: 인메모리라 재시작 시 유실 — DB(orders 테이블)로 이전 필요.
     */
    private final Map<String, OrderRequest> brokerOrderIdToRequest = new ConcurrentHashMap<>();

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

    /**
     * 주문 요청 수신 → 멱등성 검사 → 모드별 실행.
     */
    @EventListener
    public void onOrderRequest(OrderRequest request) {
        // add()가 false면 이미 본 키 → 중복 요청이므로 실행하지 않는다
        if (!seenIdempotencyKeys.add(request.idempotencyKey())) {
            log.warn("중복 주문 거부 (멱등키 재사용): {}", request.idempotencyKey());
            return;
        }
        switch (properties.mode()) {
            case SIM -> executeSim(request);
            case LIVE -> executeLive(request);
        }
    }

    /**
     * SIM: 주문 즉시 지정가 전량 체결로 간주.
     * 주의 — 이건 "이벤트 루프 검증"용이다. 슬리피지·부분체결·미체결이 없는
     * 세계이므로 전략 성과 평가에 쓰면 안 된다. 성과 평가는 Phase 3의
     * 비용 모델 있는 SimExecution(backtest 모듈)이 맡는다.
     */
    private void executeSim(OrderRequest request) {
        String brokerOrderId = "SIM-" + simOrderSeq.incrementAndGet();
        log.info("[SIM] 즉시 체결: {} {} {}주 @ {}",
                request.symbol(), request.side(), request.quantity(), request.limitPrice());
        publisher.publishEvent(new Fill(
                request.idempotencyKey(),   // Fill을 원래 주문과 연결하는 열쇠
                brokerOrderId,
                request.symbol(),
                request.side(),
                request.quantity(),
                request.limitPrice(),       // 지정가 그대로 체결됐다고 가정
                Instant.now()));
    }

    /**
     * LIVE: 키움 주문 API 호출. 여기서는 "접수"까지만 —
     * 실제 체결은 비동기로 일어나므로 WS 체결통보(OrderNotice)를 받아
     * {@link OrderNoticeHandler}가 Fill을 발행한다. 그 매핑을 위해 여기서
     * brokerOrderId → 원 주문요청을 기억해 둔다.
     */
    private void executeLive(OrderRequest request) {
        String brokerOrderId = orderService.placeOrder(request);
        brokerOrderIdToRequest.put(brokerOrderId, request);
        log.info("[LIVE] 주문 접수: {} → 주문번호 {}", request.symbol(), brokerOrderId);
        // TODO Phase 2 후반: 미체결 타임아웃 자동 취소 (OutstandingOrderService 활용)
    }

    /**
     * brokerOrderId로 원 주문요청을 찾는다. OrderNoticeHandler 전용 조회 API.
     *
     * @return 매핑이 있으면 원 주문요청, 없으면 null (수동 주문 등 이 서비스가
     *         모르는 주문일 수 있음 — 호출자가 경고 로그를 남기고 무시해야 한다)
     */
    OrderRequest findByBrokerOrderId(String brokerOrderId) {
        return brokerOrderIdToRequest.get(brokerOrderId);
    }
}

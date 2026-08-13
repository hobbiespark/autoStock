package com.autostock.execution;

import com.autostock.common.event.Fill;
import com.autostock.common.event.OrderRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
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
 *          돌려볼 수 있게 해주는 검증용 모드. (현재 기본값) BrokerPort를 거치지 않는다.
 *   LIVE : {@link BrokerPort}(현재 구현체 KiwoomBrokerAdapter)로 실제 브로커 호출.
 *          체결 확인은 WS 체결통보로 별도 수신({@link OrderNoticeHandler}).
 * </pre>
 *
 * <p><b>멱등성(idempotency)이 이 클래스의 첫 번째 책임이다.</b>
 * 이벤트 재전송, 리스너 중복 등록, 재시도 버그 — 어떤 이유로든 같은 주문 요청이
 * 두 번 도착할 수 있다. 같은 멱등키(=ClientOrderId 문자열, OrderRequest.idempotencyKey)를
 * 두 번 실행하면 돈이 두 번 나간다. 방어는 2단계다(PLAN.md ADR-6 7절):
 * <pre>
 *   1차: 인메모리 Set        — 같은 프로세스 내 즉시 중복 재수신을 조용히 버린다(빠름)
 *   2차: DB client_order_id UNIQUE — 재시작 등으로 인메모리가 리셋된 뒤의 최종 방어선
 * </pre>
 *
 * <p><b>LIVE 흐름과 UNKNOWN(ARCHITECTURE.md 6절)</b>: 주문 전송은 "저장 후 전송" 순서를
 * 지킨다(ARCHITECTURE.md 7절) — OrderEntity를 먼저 SUBMITTING 상태로 DB에 남긴 뒤에
 * 브로커를 호출한다. 브로커 호출이 타임아웃/네트워크 오류로 실패하면 "주문이 실제로
 * 나갔는지 모른다" — 이때 REJECTED로 잘못 단정하고 재시도하면 중복 주문이, SUBMITTED로
 * 잘못 단정하면 실제로 안 나간 주문을 있는 셈 치는 사고가 난다. 그래서 UNKNOWN으로
 * 남기고 {@link ReconciliationService}에게 해소를 맡긴다 — 주문 API는 무조건 재시도하지
 * 않는다(ARCHITECTURE.md 6절, 현행 429-only retry와 별개).
 */
@Component
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    /**
     * 이미 처리한 멱등키(ClientOrderId 문자열) 집합 — 1차 방어선(인메모리, 빠름).
     * ConcurrentHashMap 기반 Set이라 add()가 원자적 — "동시에 두 스레드가 같은 키를
     * 추가하면 한쪽만 true"가 보장된다. 재시작하면 사라지므로 2차 방어선(DB UNIQUE)이 있다.
     */
    private final Set<String> seenClientOrderIds = ConcurrentHashMap.newKeySet();

    /** SIM 모드 가상 주문번호 시퀀스 (SIM-1, SIM-2, ...) */
    private final AtomicLong simOrderSeq = new AtomicLong(0);

    /**
     * LIVE 모드 전용: 브로커 주문번호 → 원 주문요청 맵(인메모리 빠른 조회 경로).
     * WS 체결통보(OrderNotice)는 brokerOrderId만 들고 오기 때문에, 이걸 원래
     * OrderRequest(멱등키·수량·매수매도 방향 등)로 되돌려 찾을 방법이 있어야
     * Fill을 만들 수 있다. {@link OrderNoticeHandler}가 이 맵을 조회하고, 여기 없으면
     * OrderRepository(DB)로 폴백한다 — 재시작으로 이 맵이 유실됐을 때의 방어선.
     */
    private final Map<String, OrderRequest> brokerOrderIdToRequest = new ConcurrentHashMap<>();

    private final ExecutionProperties properties;
    private final BrokerPort brokerPort;
    private final OrderRepository orderRepository;
    private final ReconciliationService reconciliationService;
    private final ApplicationEventPublisher publisher;
    private final MeterRegistry meterRegistry;

    public ExecutionService(ExecutionProperties properties,
                            BrokerPort brokerPort,
                            OrderRepository orderRepository,
                            ReconciliationService reconciliationService,
                            ApplicationEventPublisher publisher,
                            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.brokerPort = brokerPort;
        this.orderRepository = orderRepository;
        this.reconciliationService = reconciliationService;
        this.publisher = publisher;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 주문 요청 수신 → 멱등성 검사 → 모드별 실행.
     *
     * <p>{@code order.submit.latency}: 주문 요청을 받아 "접수"(SIM은 즉시 체결, LIVE는
     * 키움 API 접수 완료)까지 걸린 시간을 잰다 — 주문 라운드트립 지연 계측(PLAN ADR-5).
     */
    @EventListener
    public void onOrderRequest(OrderRequest request) {
        // add()가 false면 이미 본 키 → 중복 요청이므로 실행하지 않는다
        if (!seenClientOrderIds.add(request.idempotencyKey())) {
            log.warn("중복 주문 거부 (멱등키 재사용): {}", request.idempotencyKey());
            return;
        }
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            switch (properties.mode()) {
                case SIM -> executeSim(request);
                case LIVE -> executeLive(request);
            }
        } finally {
            sample.stop(Timer.builder("order.submit.latency")
                    .description("주문 요청 수신부터 접수 완료까지 걸린 시간")
                    .tag("mode", properties.mode().name())
                    .register(meterRegistry));
        }
    }

    /**
     * SIM: 주문 즉시 지정가 전량 체결로 간주.
     * 주의 — 이건 "이벤트 루프 검증"용이다. 슬리피지·부분체결·미체결이 없는
     * 세계이므로 전략 성과 평가에 쓰면 안 된다. 성과 평가는 Phase 3의
     * 비용 모델 있는 SimExecution(backtest 모듈)이 맡는다.
     *
     * <p>BrokerPort는 거치지 않지만, OrderEntity는 감사·리플레이 일관성을 위해
     * SUBMITTED→FILLED로 기록한다(스펙 4절) — 다른 실행 경로와 같은 감사 흔적을 남긴다.
     */
    private void executeSim(OrderRequest request) {
        String brokerOrderId = "SIM-" + simOrderSeq.incrementAndGet();
        log.info("[SIM] 즉시 체결: {} {} {}주 @ {}",
                request.symbol(), request.side(), request.quantity(), request.limitPrice());

        OrderEntity entity = new OrderEntity(request.idempotencyKey(), request.symbol(), request.side(),
                request.quantity(), request.limitPrice(), request.strategyId());
        entity.transitionTo(OrderStatus.VALIDATED);
        entity.transitionTo(OrderStatus.SUBMITTING);
        entity.markSubmitted(brokerOrderId);
        entity.applyFill(request.quantity()); // SIM은 항상 전량 즉시 체결 가정 → FILLED
        orderRepository.save(entity);

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
     * LIVE: DB 저장 후 브로커 전송(ARCHITECTURE.md 7절) → 성공 시 SUBMITTED,
     * 타임아웃/불명 예외 시 UNKNOWN + Reconciliation 요청.
     */
    private void executeLive(OrderRequest request) {
        OrderEntity entity = new OrderEntity(request.idempotencyKey(), request.symbol(), request.side(),
                request.quantity(), request.limitPrice(), request.strategyId());
        entity.transitionTo(OrderStatus.VALIDATED);

        try {
            orderRepository.save(entity); // 전송보다 저장이 먼저 — DB UNIQUE가 2차 방어선
        } catch (DataIntegrityViolationException e) {
            // 인메모리 Set을 뚫고 들어온 진짜 중복(예: 재시작 직후 재수신) — 최종 방어선에서 스킵
            log.warn("DB client_order_id UNIQUE 제약으로 중복 주문 감지, 스킵: {}", request.idempotencyKey());
            return;
        }

        entity.transitionTo(OrderStatus.SUBMITTING);
        orderRepository.save(entity);

        try {
            BrokerOrderResult result = brokerPort.placeOrder(request);
            entity.markSubmitted(result.brokerOrderId());
            orderRepository.save(entity);
            brokerOrderIdToRequest.put(result.brokerOrderId(), request);
            log.info("[LIVE] 주문 접수: {} → 주문번호 {}", request.symbol(), result.brokerOrderId());
        } catch (Exception e) {
            // 주문 API에는 무조건적 자동 Retry를 적용하지 않는다(ARCHITECTURE.md 6절) —
            // 결과를 모르는 채로 재시도하면 중복 주문 위험이 있다. UNKNOWN으로 남기고
            // Reconciliation이 브로커 조회로 실제 상태를 확정하게 한다.
            log.error("[LIVE] 주문 전송 중 예외(타임아웃/네트워크 등으로 결과 불명) — UNKNOWN 처리: {}",
                    request.idempotencyKey(), e);
            entity.transitionTo(OrderStatus.UNKNOWN);
            orderRepository.save(entity);
            reconciliationService.requestReconcile(request.idempotencyKey());
        }
    }

    /**
     * brokerOrderId로 원 주문요청을 찾는다. OrderNoticeHandler 전용 조회 API(인메모리 1차 경로).
     *
     * @return 매핑이 있으면 원 주문요청, 없으면 null (수동 주문 등 이 서비스가
     *         모르는 주문이거나, 재시작으로 맵이 유실된 경우 — 호출자가 DB 폴백해야 한다)
     */
    OrderRequest findByBrokerOrderId(String brokerOrderId) {
        return brokerOrderIdToRequest.get(brokerOrderId);
    }
}

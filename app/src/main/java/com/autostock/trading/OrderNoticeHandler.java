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

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WS 주문체결통보(OrderNotice) → Fill 변환기.
 *
 * <p>LIVE 모드에서는 주문을 접수한다고 바로 체결되는 게 아니다. 실제 체결은
 * 키움 서버가 WS로 비동기 통보해 준다 (market 모듈이 OrderNotice로 정규화해
 * 발행). 이 클래스가 그 통보를 받아 "진짜 체결이 맞는지" 판단하고, 맞다면
 * PositionBook 등 나머지 시스템이 이해하는 공통 언어인 Fill로 바꿔 발행한다.
 * 동시에 {@link OrderEntity#applyFill(long)}로 주문 상태기계도 갱신한다.
 *
 * <p><b>부분체결 실측 확정 (2026-09-11, 19주 매수 3회 분할 체결)</b> — FID 911은
 * "이번에 새로 체결된 수량"이 아니라 <b>누적 체결량</b>이고, FID 910은 <b>누적
 * 평균 체결단가</b>다(관측: 1주@259,500 → 3주@259,833 → 19주@259,974 — 평균가라
 * 호가 단위가 아닌 값이 온다). 과거의 "통보=증분" 가정으로 각 통보를 그대로 더해
 * 포지션이 1+3+19=23주로 이중계상되는 결함이 있었다(운영 1일차 ⑥). 이제:
 * <pre>
 *   증분 수량 delta = 통보의 누적량 − OrderEntity.filledQuantity(DB 영속 누적치)
 *   증분 단가       = (누적량×평균가 − 직전 누적금액) / delta   ← 누적금액은 인메모리 추적
 * </pre>
 * delta ≤ 0이면 중복/역순 통보로 보고 무시한다. 재시작으로 인메모리 누적금액이
 * 유실되면 직전 누적금액을 "직전 누적량 × 이번 평균가"로 근사한다(오차는 평균가
 * 변동분 × 직전 누적량에 그친다 — 추정 오염을 로그로 명시).
 *
 * <p><b>실측 확정 2026-09-11</b> — 913(주문상태)의 실제 값은 "접수"/"체결" 두 문자열.
 * "접수"는 ACCEPTED 전이만, "체결"은 Fill 발행 + applyFill. 그 외("취소"/"거부" 등 —
 * 미관측, TODO 실측)는 상태기계를 오염시키지 않도록 로그만 남기고 무시한다.
 */
@Component
public class OrderNoticeHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderNoticeHandler.class);

    /** 접수 통보 상태 문자열. 실측 확정 2026-09-11(FID 913 = "접수"). */
    private static final String STATUS_ACCEPTED = "접수";

    /**
     * 체결을 의미하는 것으로 간주하는 상태 문자열 키워드.
     * 실측 확정 2026-09-11: 관측된 값은 정확히 "체결"이다. 부분체결도 같은 "체결"
     * 문자열로 온다(2026-09-11 3회 분할 체결 실측 — 별도 "부분체결" 문자열 없음).
     */
    private static final String FILLED_KEYWORD = "체결";

    /** 누적금액 추적 맵 상한 — 비정상 누수 방어(일 주문 상한 30의 넉넉한 배수). */
    private static final int MAX_TRACKED = 500;

    private final TradingService tradingService;
    private final OrderRepository orderRepository;
    private final ApplicationEventPublisher publisher;

    /**
     * brokerOrderId → 직전 누적 체결금액(수량×평균가). 증분 단가 역산용.
     * 주문 종결(902 잔량 0 또는 FILLED) 시 제거. 재시작 시 유실 — 위 Javadoc의 근사로 폴백.
     */
    private final Map<String, BigDecimal> cumulativeNotional = new ConcurrentHashMap<>();

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
        if (entity.isEmpty()) {
            // 증분 계산의 기준(영속 누적치)이 없으면 누적 통보를 안전하게 반영할 수 없다.
            // 인메모리 원 주문만으로 진행하면 이중계상 위험이 있어 대사에 맡긴다.
            log.warn("OrderEntity 없는 체결통보 — 이중계상 방지 위해 무시(Reconciliation이 복구): {}",
                    notice.brokerOrderId());
            return;
        }

        OrderEntity order = entity.get();
        String clientOrderId = original != null ? original.idempotencyKey() : order.getClientOrderId();
        // 매수/매도 방향은 통보에 없으므로 원 주문(인메모리 우선, 없으면 DB 엔티티)에서 가져온다
        Side side = original != null ? original.side() : order.getSide();

        // ── 누적 → 증분 변환 (운영 1일차 ⑥, 실측 확정 2026-09-11) ───────────────
        long cumulativeQty = notice.filledQuantity();     // FID 911 = 누적 체결량(실측)
        long previousQty = order.getFilledQuantity();
        long delta = cumulativeQty - previousQty;
        if (delta <= 0) {
            log.info("누적 체결량({})이 기존 누적({}) 이하 — 중복/역순 통보로 보고 무시: {}",
                    cumulativeQty, previousQty, notice.brokerOrderId());
            return;
        }
        BigDecimal deltaPrice = resolveDeltaPrice(notice, cumulativeQty, previousQty, delta);

        try {
            order.applyFill(delta);
            // 실측 확정 2026-09-11(FID 902): 미체결 잔량 0 = 브로커 기준 완전 소진 확정.
            // remainingQuantity == -1은 "필드 자체가 없던 통보"(과거 픽스처 등)이므로 무시.
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
        if (notice.remainingQuantity() == 0 || order.getStatus() == OrderStatus.FILLED) {
            cumulativeNotional.remove(notice.brokerOrderId());
        }

        publisher.publishEvent(new Fill(
                clientOrderId,
                notice.brokerOrderId(),
                notice.symbol(),
                side,
                delta,                    // 증분 수량 — PositionBook은 증분 합산 전제(이중계상 수정)
                deltaPrice,
                notice.timestamp()));
    }

    /**
     * 증분 체결단가 역산 — FID 910은 누적 평균가이므로(실측), 증분 단가는
     * (누적금액 − 직전 누적금액) / 증분 수량으로 되돌린다. 평균가가 없으면 null 그대로.
     */
    private BigDecimal resolveDeltaPrice(OrderNotice notice, long cumulativeQty,
                                         long previousQty, long delta) {
        BigDecimal avgPrice = notice.fillPrice();
        if (avgPrice == null || avgPrice.signum() <= 0) {
            return avgPrice;
        }
        if (cumulativeNotional.size() >= MAX_TRACKED) {
            log.warn("누적금액 추적 맵 상한({}) 도달 — 비움(단가는 평균가 폴백)", MAX_TRACKED);
            cumulativeNotional.clear();
        }
        BigDecimal cumNotional = avgPrice.multiply(BigDecimal.valueOf(cumulativeQty));
        BigDecimal prevNotional = cumulativeNotional.get(notice.brokerOrderId());
        if (prevNotional == null) {
            if (previousQty > 0) {
                // 재시작 등으로 직전 누적금액 유실 — 이번 평균가로 근사(오차 미미, 로그로 명시)
                log.info("직전 누적금액 미보유 — 평균가 근사로 증분 단가 계산: {}", notice.brokerOrderId());
            }
            prevNotional = avgPrice.multiply(BigDecimal.valueOf(previousQty));
        }
        cumulativeNotional.put(notice.brokerOrderId(), cumNotional);
        if (previousQty == 0) {
            // 첫 체결: 누적=증분이므로 평균가가 곧 증분 단가 — 원 스케일 그대로 반환
            return avgPrice;
        }
        return cumNotional.subtract(prevNotional)
                .divide(BigDecimal.valueOf(delta), MathContext.DECIMAL64)
                .setScale(4, RoundingMode.HALF_UP);
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

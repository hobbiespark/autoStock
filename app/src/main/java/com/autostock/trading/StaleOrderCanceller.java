package com.autostock.trading;

import com.autostock.market.MarketSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 미체결 주문 타임아웃 자동 취소 — 1분 주기로 미체결(SUBMITTED·ACCEPTED·PARTIALLY_FILLED) 상태가
 * 너무 오래(기본 5분, {@code execution.stale-order-timeout}) 정체된 주문을 취소한다.
 *
 * <p>존재 이유: 브로커가 접수만 하고 체결이 영영 오지 않는 경우(지정가가 안 맞거나, WS 통보가
 * 유실되는 등) 미체결 주문이 계속 살아남아 리스크 한도(동시 보유 종목 수 등)를 잠식할 수 있다.
 * 이 클래스가 오래된 주문을 정리한다.
 *
 * <p><b>대상 상태와 정체 기준(Phase 0.1, aiDoc/stale-cancel.md)</b>: LIVE에서는 WS "접수" 통보로
 * 곧 ACCEPTED가 되므로 SUBMITTED만 보면 취소가 사실상 동작하지 않았다(2026-10-01 감사 BE-P0-3).
 * 정체 기준은 주문의 마지막 상태 갱신 시각({@code updatedAt})이다 — 부분체결 주문은 "마지막 체결 후
 * 타임아웃 동안 추가 체결 없음"이면 잔량을 취소한다(체결분은 보존).
 *
 * <p><b>취소 경로는 {@link TradingService#requestCancel}로 일원화</b>한다(SSOT) — 대시보드 취소와
 * 같은 흐름(CANCEL_REQUESTED → 브로커 취소(잔량 전량) → CANCELLED 확정, 실패 시 UNKNOWN + 대사)을
 * 탄다. 목록 조회 뒤 체결 통보가 먼저 반영됐으면 최신 주문으로 "아직 정체 중인가"를 다시 판정해
 * 취소하지 않는다(R2).
 *
 * <p>시간 판단은 {@link Clock}을 주입받아 사용한다 — 테스트에서 {@link Clock#fixed}로
 * 고정해 "지금이 몇 분 지났다"를 결정론적으로 검증할 수 있게 하기 위해서다
 * (System.currentTimeMillis()를 직접 쓰면 테스트가 실제 시간 경과에 의존하게 된다).
 *
 * <p>LIVE 모드에서만 동작한다 — SIM은 즉시 체결이라 미체결 상태가 존재하지 않는다.
 */
@Component
public class StaleOrderCanceller {

    private static final Logger log = LoggerFactory.getLogger(StaleOrderCanceller.class);

    /** 정체 판정 대상 — 브로커에 살아 있는 미체결 주문 상태(취소 가능 상태와 같다). */
    static final List<OrderStatus> OPEN_STATUSES =
            List.of(OrderStatus.SUBMITTED, OrderStatus.ACCEPTED, OrderStatus.PARTIALLY_FILLED);

    private static final String REQUESTED_BY = "stale-timeout";

    private final OrderRepository orderRepository;
    private final TradingService tradingService;
    private final TradingProperties properties;
    private final Clock clock;
    private final MarketSessionService marketSession;

    public StaleOrderCanceller(OrderRepository orderRepository, TradingService tradingService,
                               TradingProperties properties, Clock clock,
                               MarketSessionService marketSession) {
        this.orderRepository = orderRepository;
        this.tradingService = tradingService;
        this.properties = properties;
        this.clock = clock;
        this.marketSession = marketSession;
    }

    /**
     * 1분 주기 점검. {@code fixedDelay}(직전 실행 종료 기준)를 쓴다 — 가상 스레드 스케줄러
     * (SimpleAsyncTaskScheduler)는 fixedRate의 밀린 회차를 <b>동시에</b> 실행하므로, PC 절전 후
     * 깨어나면 수십~수백 회차가 한꺼번에 DB 커넥션 10개를 두고 경쟁해 풀이 고갈됐다
     * (2026-09-22 실측: active=10, waiting=210, 215회 실패). fixedDelay는 밀린 회차를 1회로 합친다.
     */
    @Scheduled(fixedDelay = 60_000)
    public void cancelStaleOrders() {
        // 장외 대기 중에는 쉰다 — 장 마감 후 미체결은 브로커에서 당일 소멸하므로 취소 TR을 보낼
        // 이유가 없다. 남은 미체결은 대기 해제 후 ReconciliationService가 먼저 대사한다.
        if (!marketSession.isActive()) {
            return;
        }
        if (properties.mode() != TradingProperties.Mode.LIVE) {
            return;
        }
        Instant threshold = Instant.now(clock).minus(properties.staleOrderTimeout());
        List<OrderEntity> open = orderRepository.findByStatusIn(OPEN_STATUSES);
        for (OrderEntity order : open) {
            if (isStale(order, threshold)) {
                cancelOne(order, threshold);
            }
        }
    }

    private static boolean isStale(OrderEntity order, Instant threshold) {
        return order.getUpdatedAt().isBefore(threshold);
    }

    private void cancelOne(OrderEntity order, Instant threshold) {
        try {
            boolean requested = tradingService.requestCancel(order.getClientOrderId(), REQUESTED_BY,
                    current -> isStale(current, threshold));
            if (requested) {
                log.info("미체결 타임아웃 취소 처리: clientOrderId={} brokerOrderId={} 상태={} 체결={}/{} 마지막 갱신={}",
                        order.getClientOrderId(), order.getBrokerOrderId(), order.getStatus(),
                        order.getFilledQuantity(), order.getQuantity(), order.getUpdatedAt());
            }
        } catch (Exception e) {
            // 한 건 실패(낙관적 잠금 3회 충돌 등)가 나머지 주문 점검을 막지 않게 한다 — 다음 주기(1분)에
            // 다시 판정한다. 브로커 취소 실패는 requestCancel 안에서 UNKNOWN + 대사로 처리된다.
            log.error("미체결 타임아웃 취소 실패: clientOrderId={}", order.getClientOrderId(), e);
        }
    }
}

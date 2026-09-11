package com.autostock.trading;

import com.autostock.common.event.OrderSequenceRestored;
import com.autostock.common.util.ClientOrderId;
import com.autostock.common.util.MarketConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 재시작 시 당일 주문 일련번호 복원 (운영 1일차 ①).
 *
 * <p>결함 실측(2026-09-11): 15:09 재기동 후 인메모리 카운터가 0으로 리셋돼 수동 매수가
 * BUY-001부터 다시 생성됐고, DB UNIQUE에 3회 연속 충돌(-001~-003 스킵)한 뒤에야
 * -004로 접수됐다. 멱등 방어는 작동했지만(중복 주문 없음) 사용자는 주문이 3번
 * "조용히 안 나가는" 경험을 한다.
 *
 * <p>해소: 기동 완료 시점에 orders 테이블에서 KST 오늘 자정 이후 주문들의
 * ClientOrderId 꼬리 일련번호 최댓값을 구해 {@link OrderSequenceRestored}로 발행한다.
 * risk.DailyLimitTracker가 수신해 카운터를 그 값 이상으로 프라이밍한다 —
 * trading→risk 직접 참조 없이 이벤트로만(모듈 경계 원칙).
 *
 * <p>주의: 이 카운터는 "일련번호"이자 "일 주문 한도 계수"를 겸하므로, 복원은 한도
 * 관점에서도 올바르다(재시작으로 한도가 초기화돼 하루 상한을 우회하는 구멍도 함께 막힌다).
 */
@Component
public class OrderSequenceRestorer {

    private static final Logger log = LoggerFactory.getLogger(OrderSequenceRestorer.class);

    private final OrderRepository orderRepository;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public OrderSequenceRestorer(OrderRepository orderRepository,
                                 ApplicationEventPublisher publisher,
                                 Clock clock) {
        this.orderRepository = orderRepository;
        this.publisher = publisher;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restore() {
        LocalDate today = LocalDate.now(clock.withZone(MarketConstants.KST));
        Instant startOfDay = today.atStartOfDay(MarketConstants.KST).toInstant();
        int maxSeq = 0;
        for (OrderEntity order : orderRepository
                .findBySubmittedAtGreaterThanEqualOrderBySubmittedAtDesc(startOfDay)) {
            try {
                ClientOrderId id = ClientOrderId.parse(order.getClientOrderId());
                if (today.equals(id.date()) && id.sequence() > maxSeq) {
                    maxSeq = id.sequence();
                }
            } catch (IllegalArgumentException e) {
                // 포맷 밖 키(과거 데이터 등)는 건너뛴다 — 복원은 best-effort
                log.debug("일련번호 복원 중 포맷 밖 ClientOrderId 건너뜀: {}", order.getClientOrderId());
            }
        }
        if (maxSeq > 0) {
            log.info("당일 주문 일련번호 복원: 최대 {} → 다음 주문은 {}부터", maxSeq, maxSeq + 1);
        }
        publisher.publishEvent(new OrderSequenceRestored(today, maxSeq));
    }
}

package com.autostock.monitor;

import com.autostock.common.util.MarketConstants;
import com.autostock.trading.OrderRepository;
import com.autostock.trading.OrderStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

/**
 * {@link DailyPerformanceRecorder} 어댑터 — JPA Repository로 upsert를 구현한다.
 * 운영 코드에서 유일한 구현체(Spring이 {@link DailyReportScheduler}에 주입).
 *
 * <p><b>주문·취소 건수(OTR 관측, V14 — 실행 계획 1.7)</b>: 저장할 때 그날(KST) 접수된 주문을 주문 테이블에서 세어 함께
 * 남긴다. 목표치 없이 기록만 한다(aiDoc/observability.md).
 */
@Component
public class DailyPerformanceService implements DailyPerformanceRecorder {

    /** 브로커로 보냈거나 보내려던 주문 — 전송 전 단계(CREATED·VALIDATED)만 뺀다. */
    static final Set<OrderStatus> SUBMITTED_STATUSES =
            EnumSet.complementOf(EnumSet.of(OrderStatus.CREATED, OrderStatus.VALIDATED));
    /** 취소 요청을 보낸 주문(현재 상태 기준). */
    static final Set<OrderStatus> CANCELLED_STATUSES = EnumSet.of(OrderStatus.CANCELLED, OrderStatus.CANCEL_REQUESTED);

    private final DailyPerformanceRepository repository;
    private final OrderRepository orderRepository;
    private final Clock clock;

    public DailyPerformanceService(DailyPerformanceRepository repository, OrderRepository orderRepository, Clock clock) {
        this.repository = repository;
        this.orderRepository = orderRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void saveSnapshot(LocalDate tradeDate, BigDecimal realizedPnl, int orderCount, int fillCount,
                             double avgSlippageBps, double maxSlippageBps,
                             boolean conservativeMode, boolean killSwitchEngaged) {
        DailyPerformanceEntity entity = repository.findByTradeDate(tradeDate)
                .orElseGet(() -> new DailyPerformanceEntity(tradeDate, clock.instant()));
        entity.update(realizedPnl, orderCount, fillCount, avgSlippageBps, maxSlippageBps,
                conservativeMode, killSwitchEngaged);
        Instant from = tradeDate.atStartOfDay(MarketConstants.KST).toInstant();
        Instant to = tradeDate.plusDays(1).atStartOfDay(MarketConstants.KST).toInstant();
        entity.recordOrderActivity(
                (int) orderRepository.countBySubmittedAtGreaterThanEqualAndSubmittedAtLessThanAndStatusIn(from, to, SUBMITTED_STATUSES),
                (int) orderRepository.countBySubmittedAtGreaterThanEqualAndSubmittedAtLessThanAndStatusIn(from, to, CANCELLED_STATUSES));
        repository.save(entity);
    }
}

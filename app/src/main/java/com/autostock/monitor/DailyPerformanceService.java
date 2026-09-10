package com.autostock.monitor;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@link DailyPerformanceRecorder} 어댑터 — JPA Repository로 upsert를 구현한다.
 * 운영 코드에서 유일한 구현체(Spring이 {@link DailyReportScheduler}에 주입).
 */
@Component
public class DailyPerformanceService implements DailyPerformanceRecorder {

    private final DailyPerformanceRepository repository;

    public DailyPerformanceService(DailyPerformanceRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void saveSnapshot(LocalDate tradeDate, BigDecimal realizedPnl, int orderCount, int fillCount,
                             double avgSlippageBps, double maxSlippageBps,
                             boolean conservativeMode, boolean killSwitchEngaged) {
        DailyPerformanceEntity entity = repository.findByTradeDate(tradeDate)
                .orElseGet(() -> new DailyPerformanceEntity(tradeDate));
        entity.update(realizedPnl, orderCount, fillCount, avgSlippageBps, maxSlippageBps,
                conservativeMode, killSwitchEngaged);
        repository.save(entity);
    }
}

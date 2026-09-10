package com.autostock.monitor;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DailyPerformanceService — upsert 계약 검증(FE-2, PLAN.md ADR-10 확장표).
 * "trade_date에 해당 행이 있으면 갱신, 없으면 생성" — 둘 다 repository.save()를 딱 1번만
 * 호출하고, 새 INSERT를 만들지 않는지(즉 findByTradeDate 결과를 재사용하는지)를 확인한다.
 */
class DailyPerformanceServiceTest {

    private final DailyPerformanceRepository repository = mock(DailyPerformanceRepository.class);
    private final DailyPerformanceService service = new DailyPerformanceService(repository);

    @Test
    void 해당_날짜에_행이_없으면_새로_생성해서_저장한다() {
        LocalDate tradeDate = LocalDate.of(2026, 9, 11);
        when(repository.findByTradeDate(tradeDate)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.saveSnapshot(tradeDate, new BigDecimal("15000"), 3, 2, 4.5, 9.1, false, false);

        verify(repository).findByTradeDate(tradeDate);
        var captor = org.mockito.ArgumentCaptor.forClass(DailyPerformanceEntity.class);
        verify(repository).save(captor.capture());
        DailyPerformanceEntity saved = captor.getValue();
        assertEquals(tradeDate, saved.getTradeDate());
        assertEquals(new BigDecimal("15000"), saved.getRealizedPnl());
        assertEquals(3, saved.getOrderCount());
        assertEquals(2, saved.getFillCount());
        assertEquals(4.5, saved.getAvgSlippageBps());
        assertEquals(9.1, saved.getMaxSlippageBps());
    }

    @Test
    void 해당_날짜에_기존_행이_있으면_같은_엔티티를_갱신해서_저장한다_새로_생성하지_않는다() {
        LocalDate tradeDate = LocalDate.of(2026, 9, 11);
        DailyPerformanceEntity existing = new DailyPerformanceEntity(tradeDate);
        existing.update(new BigDecimal("1000"), 1, 1, 1.0, 1.0, false, false);
        when(repository.findByTradeDate(tradeDate)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.saveSnapshot(tradeDate, new BigDecimal("22000"), 5, 4, 6.0, 12.0, true, true);

        var captor = org.mockito.ArgumentCaptor.forClass(DailyPerformanceEntity.class);
        verify(repository).save(captor.capture());
        DailyPerformanceEntity saved = captor.getValue();
        assertEquals(existing, saved); // 새 인스턴스가 아니라 기존 엔티티를 그대로 갱신
        assertEquals(new BigDecimal("22000"), saved.getRealizedPnl());
        assertEquals(5, saved.getOrderCount());
        assertEquals(4, saved.getFillCount());
        assertEquals(true, saved.isConservativeMode());
        assertEquals(true, saved.isKillSwitchEngaged());
        verify(repository, never()).findByTradeDateGreaterThanEqualOrderByTradeDateAsc(any());
    }
}

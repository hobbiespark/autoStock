package com.autostock.monitor;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PerformanceController — days 기간 필터(기본 90·최대 90)와 KST 기준 날짜 계산,
 * View DTO 변환을 검증한다(FE-2, PLAN.md ADR-10 확장표).
 */
class PerformanceControllerTest {

    private final DailyPerformanceRepository repository = mock(DailyPerformanceRepository.class);
    // 2026-09-11T00:30:00Z = KST 2026-09-11 09:30 → KST 날짜는 2026-09-11
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-11T00:30:00Z"), ZoneOffset.UTC);
    private final PerformanceController controller = new PerformanceController(repository, clock);

    @Test
    void days_생략시_기본값_90일로_조회한다() {
        when(repository.findByTradeDateGreaterThanEqualOrderByTradeDateAsc(any())).thenReturn(List.of());

        controller.daily(null);

        verify(repository).findByTradeDateGreaterThanEqualOrderByTradeDateAsc(LocalDate.of(2026, 9, 11).minusDays(90));
    }

    @Test
    void days_90_초과_요청은_90일로_보정한다() {
        when(repository.findByTradeDateGreaterThanEqualOrderByTradeDateAsc(any())).thenReturn(List.of());

        controller.daily(9000);

        verify(repository).findByTradeDateGreaterThanEqualOrderByTradeDateAsc(LocalDate.of(2026, 9, 11).minusDays(90));
    }

    @Test
    void 엔티티를_View_DTO로_변환하고_날짜_오름차순_그대로_반환한다() {
        DailyPerformanceEntity e1 = new DailyPerformanceEntity(LocalDate.of(2026, 9, 9));
        e1.update(new BigDecimal("1000"), 2, 1, 3.0, 5.0, false, false);
        DailyPerformanceEntity e2 = new DailyPerformanceEntity(LocalDate.of(2026, 9, 10));
        e2.update(new BigDecimal("-500"), 1, 1, 2.0, 2.0, true, false);
        when(repository.findByTradeDateGreaterThanEqualOrderByTradeDateAsc(any())).thenReturn(List.of(e1, e2));

        var result = controller.daily(7);

        assertEquals(2, result.size());
        assertEquals(LocalDate.of(2026, 9, 9), result.get(0).tradeDate());
        assertEquals(new BigDecimal("1000"), result.get(0).realizedPnl());
        assertEquals(LocalDate.of(2026, 9, 10), result.get(1).tradeDate());
        assertEquals(true, result.get(1).conservativeMode());
    }
}

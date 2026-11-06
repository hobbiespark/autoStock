package com.autostock.monitor;

import com.autostock.common.util.MarketConstants;
import com.autostock.monitor.view.DailyPerformanceView;
import com.autostock.monitor.view.PerformancePeriodView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * 일별 성과 추이 조회 API (FE-2, PLAN.md ADR-10 확장표) — {@code GET /api/performance/daily?days=90}.
 *
 * <p>{@link DailyPerformanceRepository}(monitor 자체 소유)만 조회하므로 별도 Facade 없이
 * 이 컨트롤러가 View DTO 변환까지 직접 한다(불필요한 분산 패턴 금지, ARCHITECTURE.md 규칙 20).
 */
@RestController
@RequestMapping("/api/performance")
public class PerformanceController {

    private static final int DEFAULT_DAYS = 90;
    private static final int MAX_DAYS = 90;

    private final DailyPerformanceRepository repository;
    private final Clock clock;
    /** 성과 측정 시작일(2026-10-22) — 운용 자금 2,000만 원 전 기록은 추이에 넣지 않는다. */
    private final PerformanceProperties performance;
    /** 전략 변경 구간 비교 — 빈이 없으면(시험 직접 생성) 빈 목록. */
    private PerformancePeriods periods;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void periods(PerformancePeriods periods) {
        this.periods = periods;
    }

    /** 시작일 없는 생성자 — 시험용(예전 동작). */
    public PerformanceController(DailyPerformanceRepository repository, Clock clock) {
        this(repository, clock, PerformanceProperties.none());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PerformanceController(DailyPerformanceRepository repository, Clock clock, PerformanceProperties performance) {
        this.repository = repository;
        this.clock = clock;
        this.performance = performance == null ? PerformanceProperties.none() : performance;
    }

    @GetMapping("/daily")
    public List<DailyPerformanceView> daily(@RequestParam(required = false) Integer days) {
        int effectiveDays = clampDays(days);
        // 2026-11-06: 전략 변경 구간이 있으면 첫 구간부터(변경 전후를 함께 본다), 없으면 성과 측정 시작일부터
        LocalDate since = performance.trendClamp(LocalDate.now(clock.withZone(MarketConstants.KST)).minusDays(effectiveDays));
        return repository.findByTradeDateGreaterThanEqualOrderByTradeDateAsc(since).stream()
                .map(PerformanceController::toView)
                .toList();
    }

    /**
     * 전략 변경 구간 비교(2026-11-06) — {@code GET /api/performance/periods}. 구간 설정({@code monitor.performance.periods})이
     * 없으면 빈 목록.
     */
    @GetMapping("/periods")
    public List<PerformancePeriodView> periods() {
        return periods == null ? List.of() : periods.periods();
    }

    private static int clampDays(Integer days) {
        if (days == null || days <= 0) {
            return DEFAULT_DAYS;
        }
        return Math.min(days, MAX_DAYS);
    }

    private static DailyPerformanceView toView(DailyPerformanceEntity entity) {
        return new DailyPerformanceView(
                entity.getTradeDate(),
                entity.getRealizedPnl(),
                entity.getOrderCount(),
                entity.getFillCount(),
                entity.getAvgSlippageBps(),
                entity.getMaxSlippageBps(),
                entity.isConservativeMode(),
                entity.isKillSwitchEngaged());
    }
}

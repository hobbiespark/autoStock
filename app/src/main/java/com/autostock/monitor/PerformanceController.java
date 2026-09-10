package com.autostock.monitor;

import com.autostock.common.util.MarketConstants;
import com.autostock.monitor.view.DailyPerformanceView;
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

    public PerformanceController(DailyPerformanceRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @GetMapping("/daily")
    public List<DailyPerformanceView> daily(@RequestParam(required = false) Integer days) {
        int effectiveDays = clampDays(days);
        LocalDate since = LocalDate.now(clock.withZone(MarketConstants.KST)).minusDays(effectiveDays);
        return repository.findByTradeDateGreaterThanEqualOrderByTradeDateAsc(since).stream()
                .map(PerformanceController::toView)
                .toList();
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

package com.autostock.monitor;

import com.autostock.common.util.MarketConstants;
import com.autostock.monitor.view.PerformancePeriodView;
import com.autostock.portfolio.StrategyBook;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * 전략 변경 전후 성과 비교(2026-11-06, 사용자 "전략 변경 시 전후 수익률 비교 가능한 성과 보이게") — 설정의 변경 구간
 * ({@link PerformanceProperties#periods()})마다 청산 성과를 센다. 운용 자금이 구간마다 달라(2,000만·500만·200만 원)
 * 금액이 아니라 <b>운용 자금 대비 수익률·거래일당 평균·거래당 수익률(실현손익 ÷ 매도 원가)</b>로 비교한다.
 * 실현손익만 센다(보유 중 평가손익은 넣지 않는다 — 일별 성과와 같은 기준).
 */
@Service
public class PerformancePeriods {

    private static final int PCT_SCALE = 3;

    private final PerformanceProperties performance;
    private final StrategyBook strategyBook;
    private final DailyPerformanceRepository dailyRepository;
    private final Clock clock;

    public PerformancePeriods(PerformanceProperties performance, StrategyBook strategyBook,
                              DailyPerformanceRepository dailyRepository, Clock clock) {
        this.performance = performance;
        this.strategyBook = strategyBook;
        this.dailyRepository = dailyRepository;
        this.clock = clock;
    }

    /** 구간별 성과(오래된 구간부터). 구간 설정이 없으면 빈 목록. */
    public List<PerformancePeriodView> periods() {
        LocalDate today = LocalDate.now(clock.withZone(MarketConstants.KST));
        return compare(performance.periods(), today, strategyBook::periodStats,
                (from, to) -> (int) dailyRepository.countByTradeDateBetween(from, to));
    }

    /**
     * 순수 계산 — 구간 i는 since_i ~ (since_{i+1} − 1일), 마지막 구간은 오늘까지. 아직 시작하지 않은 구간(시작일 &gt; 오늘)은 뺀다.
     *
     * @param stats       (시작일, 끝일) → 전략별 청산 성과
     * @param tradingDays (시작일, 끝일) → 거래일 수
     */
    static List<PerformancePeriodView> compare(List<PerformanceProperties.Period> periods, LocalDate today,
                                               BiFunction<LocalDate, LocalDate, List<StrategyBook.PeriodStat>> stats,
                                               BiFunction<LocalDate, LocalDate, Integer> tradingDays) {
        List<PerformancePeriodView> out = new ArrayList<>();
        for (int i = 0; i < periods.size(); i++) {
            PerformanceProperties.Period period = periods.get(i);
            if (period.since().isAfter(today)) {
                continue;
            }
            boolean current = i == periods.size() - 1 || periods.get(i + 1).since().isAfter(today);
            LocalDate to = current ? today : periods.get(i + 1).since().minusDays(1);
            List<StrategyBook.PeriodStat> rows = stats.apply(period.since(), to);
            int days = tradingDays.apply(period.since(), to);
            BigDecimal pnl = BigDecimal.ZERO;
            BigDecimal cost = BigDecimal.ZERO;
            int exits = 0;
            int wins = 0;
            List<PerformancePeriodView.StrategyRow> strategies = new ArrayList<>();
            for (StrategyBook.PeriodStat row : rows) {
                pnl = pnl.add(row.realizedPnl());
                cost = cost.add(row.exitCost());
                exits += row.exits();
                wins += row.wins();
                strategies.add(new PerformancePeriodView.StrategyRow(row.strategyId(), row.exits(), row.wins(),
                        pct(row.wins(), row.exits()), row.realizedPnl(), ratioPct(row.realizedPnl(), period.capitalKrw()),
                        ratioPct(row.realizedPnl(), cost(row.exitCost()))));
            }
            BigDecimal capitalPct = ratioPct(pnl, period.capitalKrw());
            BigDecimal dailyPct = capitalPct == null || days <= 0 ? null
                    : capitalPct.divide(BigDecimal.valueOf(days), PCT_SCALE, RoundingMode.HALF_UP);
            out.add(new PerformancePeriodView(period.label(), period.since(), to, current, period.capitalKrw(), days, pnl,
                    capitalPct, dailyPct, exits, wins, pct(wins, exits), ratioPct(pnl, cost(cost)), List.copyOf(strategies)));
        }
        return out;
    }

    private static long cost(BigDecimal cost) {
        return cost.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** value ÷ base × 100. base가 0이면 null. */
    static BigDecimal ratioPct(BigDecimal value, long base) {
        if (base <= 0 || value == null) {
            return null;
        }
        return value.multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(base), PCT_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal pct(int part, int whole) {
        return whole <= 0 ? null : ratioPct(BigDecimal.valueOf(part), whole);
    }
}

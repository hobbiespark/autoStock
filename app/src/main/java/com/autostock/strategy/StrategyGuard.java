package com.autostock.strategy;

import com.autostock.common.util.MarketConstants;
import com.autostock.market.MarketCalendarService;
import com.autostock.monitor.TradingSystemManager;
import com.autostock.monitor.TradingSystemStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 다전략 공통 실행 조건(2026-10-16) — C3와 같은 이중 가드: 전략 설정이 켜져 있고, 운영 상태기계가 RUNNING이고, 오늘이 거래일.
 */
@Component
public class StrategyGuard {

    private static final Logger log = LoggerFactory.getLogger(StrategyGuard.class);

    private final TradingSystemManager tradingSystemManager;
    private final MarketCalendarService calendar;
    private final Clock clock;

    public StrategyGuard(TradingSystemManager tradingSystemManager, MarketCalendarService calendar, Clock clock) {
        this.tradingSystemManager = tradingSystemManager;
        this.calendar = calendar;
        this.clock = clock;
    }

    /** 지금 매매 판단을 해도 되나. */
    public boolean active(boolean enabled) {
        return enabled && tradingSystemManager.status() == TradingSystemStatus.RUNNING && calendar.isTradingDay(today());
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(MarketConstants.KST));
    }

    public LocalTime now() {
        return LocalTime.now(clock.withZone(MarketConstants.KST));
    }

    /**
     * 예약 작업이 너무 늦게 돌았나(2026-10-21, aiDoc/upgrade-2026-10/20-log-review-2026-10-21.md) — PC가 절전에서 깨어나면
     * 그 사이 놓친 예약 작업이 한꺼번에 돈다(10/19 22:04·10/20 21:50: 09:01 차트 판단·15:15 종가 후보·08:50 단타 준비가
     * 밤에 돌았다). 지금이 deadline 이후면 한 줄을 남기고 true — 그날 그 작업은 건너뛴다. 매도·매수 신호는 RiskGate의 장 시간
     * 가드가 어차피 막지만, 판단 기록·요약 알림이 그날의 판단처럼 남는 것을 막는다.
     *
     * @param job      로그에 쓸 작업 이름(예: "ON-CLOSE 15:15 후보")
     * @param deadline 이 시각부터는 늦은 것으로 본다(KST)
     */
    public boolean tooLate(String job, LocalTime deadline) {
        LocalTime now = now();
        if (now.isBefore(deadline)) {
            return false;
        }
        log.info("{}: 예약 시각을 지나 {}에 실행됨(절전 복귀 등으로 늦게 깨어남) — {} 이후라 오늘은 건너뜀", job,
                now.withNano(0), deadline);
        return true;
    }

    public boolean isTradingDay(LocalDate day) {
        return calendar.isTradingDay(day);
    }

    public TradingSystemStatus status() {
        return tradingSystemManager.status();
    }
}

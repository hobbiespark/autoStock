package com.autostock.strategy;

import com.autostock.common.util.MarketConstants;
import com.autostock.market.MarketCalendarService;
import com.autostock.monitor.TradingSystemManager;
import com.autostock.monitor.TradingSystemStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 다전략 공통 실행 조건(2026-10-16) — C3와 같은 이중 가드: 전략 설정이 켜져 있고, 운영 상태기계가 RUNNING이고, 오늘이 거래일.
 */
@Component
public class StrategyGuard {

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

    public boolean isTradingDay(LocalDate day) {
        return calendar.isTradingDay(day);
    }

    public TradingSystemStatus status() {
        return tradingSystemManager.status();
    }
}

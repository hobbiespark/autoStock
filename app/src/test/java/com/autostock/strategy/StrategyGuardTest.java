package com.autostock.strategy;

import com.autostock.market.MarketCalendarService;
import com.autostock.monitor.TradingSystemManager;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** 늦게 깨어난 예약 작업 거르기(2026-10-21) — 절전 복귀 뒤 밤에 도는 15:15·09:01 작업을 건너뛴다. */
class StrategyGuardTest {

    private static StrategyGuard at(String kst) {
        Clock clock = Clock.fixed(LocalDateTime.parse(kst).atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneOffset.UTC);
        return new StrategyGuard(mock(TradingSystemManager.class), mock(MarketCalendarService.class), clock);
    }

    @Test
    void 마감_전이면_늦지_않았다() {
        assertFalse(at("2026-10-21T15:15:00").tooLate("ON-CLOSE 15:15 후보 선정", LocalTime.of(15, 25)));
        assertFalse(at("2026-10-21T09:01:00").tooLate("TA 09:01 판단", LocalTime.of(15, 0)));
    }

    @Test
    void 마감_시각부터는_늦었다_절전_복귀로_밤에_돈_작업() {
        assertTrue(at("2026-10-21T15:25:00").tooLate("ON-CLOSE 15:15 후보 선정", LocalTime.of(15, 25)));
        assertTrue(at("2026-10-20T21:50:52").tooLate("TA 09:01 판단", LocalTime.of(15, 0)));
    }
}

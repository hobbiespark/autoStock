package com.autostock.common.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TradingCalendar — 주말/휴장일 판정, 장 시간 경계값 검증.
 */
class TradingCalendarTest {

    @Test
    void 평일이면서_휴장일_목록에_없으면_거래일이다() {
        // 2026-08-13은 목요일, 휴장일 목록에 없음
        assertTrue(TradingCalendar.isTradingDay(LocalDate.of(2026, 8, 13)));
    }

    @Test
    void 토요일은_거래일이_아니다() {
        // 2026-08-15는 토요일
        assertFalse(TradingCalendar.isTradingDay(LocalDate.of(2026, 8, 15)));
    }

    @Test
    void 일요일은_거래일이_아니다() {
        // 2026-08-16은 일요일
        assertFalse(TradingCalendar.isTradingDay(LocalDate.of(2026, 8, 16)));
    }

    @Test
    void 신정은_거래일이_아니다() {
        assertFalse(TradingCalendar.isTradingDay(LocalDate.of(2026, 1, 1)));
    }

    @Test
    void 설연휴_전체가_거래일이_아니다() {
        assertFalse(TradingCalendar.isTradingDay(LocalDate.of(2026, 2, 16)));
        assertFalse(TradingCalendar.isTradingDay(LocalDate.of(2026, 2, 17)));
        assertFalse(TradingCalendar.isTradingDay(LocalDate.of(2026, 2, 18)));
    }

    @Test
    void 추석연휴_전체가_거래일이_아니다() {
        assertFalse(TradingCalendar.isTradingDay(LocalDate.of(2026, 9, 24)));
        assertFalse(TradingCalendar.isTradingDay(LocalDate.of(2026, 9, 25)));
        assertFalse(TradingCalendar.isTradingDay(LocalDate.of(2026, 9, 26)));
    }

    @Test
    void 연말휴장일은_거래일이_아니다() {
        assertFalse(TradingCalendar.isTradingDay(LocalDate.of(2026, 12, 31)));
    }

    @Test
    void 휴장일_다음날은_평일이면_거래일이다() {
        // 2026-01-02(금)은 신정 다음날, 평일이므로 거래일
        assertTrue(TradingCalendar.isTradingDay(LocalDate.of(2026, 1, 2)));
    }

    @Test
    void 정규장_시작시각_09시_정각은_장중이다() {
        assertTrue(TradingCalendar.isMarketHours(LocalDateTime.of(LocalDate.of(2026, 8, 13), LocalTime.of(9, 0))));
    }

    @Test
    void 정규장_종료시각_15시30분_정각은_장중이다() {
        assertTrue(TradingCalendar.isMarketHours(LocalDateTime.of(LocalDate.of(2026, 8, 13), LocalTime.of(15, 30))));
    }

    @Test
    void 정규장_시작_1분전은_장외다() {
        assertFalse(TradingCalendar.isMarketHours(LocalDateTime.of(LocalDate.of(2026, 8, 13), LocalTime.of(8, 59))));
    }

    @Test
    void 정규장_종료_1분후는_장외다() {
        assertFalse(TradingCalendar.isMarketHours(LocalDateTime.of(LocalDate.of(2026, 8, 13), LocalTime.of(15, 31))));
    }

    @Test
    void 장중_시각은_장중이다() {
        assertTrue(TradingCalendar.isMarketHours(LocalDateTime.of(LocalDate.of(2026, 8, 13), LocalTime.of(12, 0))));
    }
}

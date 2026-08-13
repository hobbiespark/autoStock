package com.autostock.marketdata;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MarketCalendarService 검증 — DB 우선/하드코딩 폴백 판정, 주말 처리, 연도 단위 캐시 동작을 확인한다.
 * 실제 DB 없이 {@link MarketHolidayRepository}를 mock으로 대체한다(순수 mock 테스트).
 */
class MarketCalendarServiceTest {

    @Test
    void 주말은_DB_조회_없이_바로_거래일_아님으로_판정한다() {
        MarketHolidayRepository repository = mock(MarketHolidayRepository.class);
        MarketCalendarService service = new MarketCalendarService(repository);

        // 2026-08-15는 토요일
        assertFalse(service.isTradingDay(LocalDate.of(2026, 8, 15)));
        verify(repository, times(0)).findByHolidayDateBetween(any(), any());
    }

    @Test
    void DB에_해당_연도_데이터가_있으면_DB_기준으로_판정한다() {
        MarketHolidayRepository repository = mock(MarketHolidayRepository.class);
        LocalDate holiday = LocalDate.of(2027, 1, 1);
        when(repository.findByHolidayDateBetween(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31)))
                .thenReturn(List.of(new MarketHolidayEntity(holiday, "신정", "DATA_GO_KR", false, Instant.now())));
        MarketCalendarService service = new MarketCalendarService(repository);

        assertFalse(service.isTradingDay(holiday), "DB에 등록된 휴장일은 거래일이 아니어야 함");
        // 2027-01-04는 월요일이고 DB에 없는 날 — DB 데이터가 있는 연도이므로 하드코딩이 아니라
        // "DB에 없으니 거래일"로 판정돼야 한다(TradingCalendar 2026년 하드코딩과 무관).
        assertTrue(service.isTradingDay(LocalDate.of(2027, 1, 4)));
    }

    @Test
    void DB에_해당_연도_데이터가_없으면_TradingCalendar_하드코딩으로_폴백한다() {
        MarketHolidayRepository repository = mock(MarketHolidayRepository.class);
        when(repository.findByHolidayDateBetween(any(), any())).thenReturn(List.of());
        MarketCalendarService service = new MarketCalendarService(repository);

        // 2026-01-01은 TradingCalendar 하드코딩 목록의 신정 — 폴백 경로로도 휴장일 판정이 나와야 한다.
        assertFalse(service.isTradingDay(LocalDate.of(2026, 1, 1)));
        // 2026-08-13(목)은 TradingCalendar 하드코딩상 거래일.
        assertTrue(service.isTradingDay(LocalDate.of(2026, 8, 13)));
    }

    @Test
    void 같은_연도를_반복_조회해도_repository는_한_번만_호출된다() {
        MarketHolidayRepository repository = mock(MarketHolidayRepository.class);
        when(repository.findByHolidayDateBetween(any(), any())).thenReturn(List.of());
        MarketCalendarService service = new MarketCalendarService(repository);

        service.isTradingDay(LocalDate.of(2026, 8, 13));
        service.isTradingDay(LocalDate.of(2026, 8, 14));
        service.isTradingDay(LocalDate.of(2026, 9, 1));

        verify(repository, times(1)).findByHolidayDateBetween(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
    }

    @Test
    void evictYear_호출후_같은_연도를_다시_조회하면_repository를_재호출한다() {
        MarketHolidayRepository repository = mock(MarketHolidayRepository.class);
        when(repository.findByHolidayDateBetween(any(), any())).thenReturn(List.of());
        MarketCalendarService service = new MarketCalendarService(repository);

        service.isTradingDay(LocalDate.of(2026, 8, 13));
        service.evictYear(2026);
        service.isTradingDay(LocalDate.of(2026, 8, 13));

        verify(repository, times(2)).findByHolidayDateBetween(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
    }

    @Test
    void isMarketHours는_TradingCalendar에_그대로_위임한다() {
        MarketHolidayRepository repository = mock(MarketHolidayRepository.class);
        MarketCalendarService service = new MarketCalendarService(repository);

        assertTrue(service.isMarketHours(LocalDate.of(2026, 8, 13).atTime(10, 0)));
        assertFalse(service.isMarketHours(LocalDate.of(2026, 8, 13).atTime(20, 0)));
    }
}

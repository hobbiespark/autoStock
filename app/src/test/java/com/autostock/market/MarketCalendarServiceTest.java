package com.autostock.market;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    // ── 거래일 세기(실행 계획 1.1 — C3 판단 주기를 백테스트와 같은 21봉으로) ─────────────────

    @Test
    void 거래일_수는_주말과_휴장일을_빼고_끝날만_포함해_센다() {
        MarketHolidayRepository repository = mock(MarketHolidayRepository.class);
        when(repository.findByHolidayDateBetween(any(), any())).thenReturn(List.of()); // 폴백 달력(추석·10/5·10/9 포함)
        MarketCalendarService service = new MarketCalendarService(repository);

        // 9/22(화) 다음 날부터 10/2(금)까지: 9/23, 9/28, 9/29, 9/30, 10/1, 10/2 — 9/24~26 추석 연휴·주말 제외
        assertEquals(6, service.tradingDaysBetween(LocalDate.of(2026, 9, 22), LocalDate.of(2026, 10, 2)));
        // 10/2(금) → 10/6(화): 주말·10/5 대체공휴일을 건너 1일
        assertEquals(1, service.tradingDaysBetween(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 6)));
        // 같거나 거꾸로 된 구간은 0
        assertEquals(0, service.tradingDaysBetween(LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 6)));
        assertEquals(0, service.tradingDaysBetween(LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 2)));
    }

    @Test
    void 거래일_n개_뒤는_휴장일을_건너뛴_n번째_거래일이다() {
        MarketHolidayRepository repository = mock(MarketHolidayRepository.class);
        when(repository.findByHolidayDateBetween(any(), any())).thenReturn(List.of());
        MarketCalendarService service = new MarketCalendarService(repository);

        // 10/1(목)에서 21거래일 — 10/5·10/9 휴장 포함, 역일 21일(10/22)이 아니라 11/3(화)
        LocalDate due = service.plusTradingDays(LocalDate.of(2026, 10, 1), 21);

        assertEquals(LocalDate.of(2026, 11, 3), due);
        assertEquals(21, service.tradingDaysBetween(LocalDate.of(2026, 10, 1), due));
        assertEquals(20, service.tradingDaysBetween(LocalDate.of(2026, 10, 1), due.minusDays(1)));
        assertEquals(LocalDate.of(2026, 10, 1), service.plusTradingDays(LocalDate.of(2026, 10, 1), 0));
    }
}

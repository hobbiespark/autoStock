package com.autostock.market;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MarketSessionService 단위테스트 — 경계 시각(08:30/16:00), 주말·휴장일, 비활성 플래그, 서버 존 무관성.
 * 거래일 판정은 mock MarketCalendarService(평일이면서 지정 휴장일이 아니면 거래일)로 대체한다.
 */
class MarketSessionServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate WED = LocalDate.of(2026, 9, 30);   // 수요일(거래일)
    private static final LocalDate HOLIDAY = LocalDate.of(2026, 10, 5); // 테스트용 평일 휴장일(월)

    private MarketCalendarService calendar;

    @BeforeEach
    void setUp() {
        calendar = mock(MarketCalendarService.class);
        when(calendar.isTradingDay(any())).thenAnswer(inv -> {
            LocalDate d = inv.getArgument(0);
            boolean weekend = d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
            return !weekend && !Set.of(HOLIDAY).contains(d);
        });
    }

    private MarketSessionService serviceAt(LocalDateTime kst) {
        Clock clock = Clock.fixed(kst.atZone(KST).toInstant(), ZoneId.of("UTC")); // 서버 존이 UTC여도 KST로 판정해야 한다
        return new MarketSessionService(calendar, clock, true, LocalTime.of(8, 30), LocalTime.of(16, 0));
    }

    @Test
    void 거래일_장대응_창_안이면_ACTIVE() {
        assertEquals(MarketSession.ACTIVE, serviceAt(WED.atTime(8, 30)).current());   // 경계 포함
        assertEquals(MarketSession.ACTIVE, serviceAt(WED.atTime(12, 0)).current());
        assertEquals(MarketSession.ACTIVE, serviceAt(WED.atTime(15, 59, 59)).current());
    }

    @Test
    void 거래일_장대응_창_밖이면_STANDBY() {
        assertEquals(MarketSession.STANDBY, serviceAt(WED.atTime(8, 29, 59)).current());
        assertEquals(MarketSession.STANDBY, serviceAt(WED.atTime(16, 0)).current());   // 경계 제외
        assertEquals(MarketSession.STANDBY, serviceAt(WED.atTime(22, 30)).current());
        assertEquals(MarketSession.STANDBY, serviceAt(WED.atTime(3, 0)).current());
    }

    @Test
    void 주말과_휴장일은_종일_STANDBY() {
        assertEquals(MarketSession.STANDBY, serviceAt(LocalDate.of(2026, 10, 3).atTime(10, 0)).current()); // 토
        assertEquals(MarketSession.STANDBY, serviceAt(HOLIDAY.atTime(10, 0)).current());
    }

    @Test
    void 비활성이면_항상_ACTIVE() {
        Clock clock = Clock.fixed(WED.atTime(22, 0).atZone(KST).toInstant(), KST);
        MarketSessionService disabled = new MarketSessionService(calendar, clock, false,
                LocalTime.of(8, 30), LocalTime.of(16, 0));
        assertEquals(MarketSession.ACTIVE, disabled.current());
    }

    @Test
    void 다음_활성화_시각은_주말과_휴장일을_건너뛴다() {
        MarketSessionService s = serviceAt(WED.atTime(16, 0));
        // 금요일 장 마감 후 → 토·일·월(휴장) 건너뛰고 화요일 08:30
        LocalDate friday = LocalDate.of(2026, 10, 2);
        assertEquals(LocalDate.of(2026, 10, 6).atTime(8, 30),
                s.nextWake(friday.atTime(16, 0).atZone(KST)).toLocalDateTime());
        // 거래일 새벽 → 당일 08:30
        assertEquals(WED.atTime(8, 30), s.nextWake(WED.atTime(3, 0).atZone(KST)).toLocalDateTime());
    }

    @Test
    void 문자열_설정을_파싱하고_역전된_시각은_거부한다() {
        Clock clock = Clock.systemUTC();
        MarketSessionService parsed = new MarketSessionService(calendar, clock, true, "07:30", "20:30");
        assertEquals(LocalTime.of(7, 30), parsed.wakeTime());
        assertEquals(LocalTime.of(20, 30), parsed.sleepTime());

        assertThrows(IllegalArgumentException.class,
                () -> new MarketSessionService(calendar, clock, true, "16:00", "08:30"));
    }
}

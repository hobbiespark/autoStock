package com.autostock.fx;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 설계 5장 노출액(D-28). 금액은 합성이다. */
class FxExposureTest {

    private static final YearMonth DEC = YearMonth.of(2026, 12);

    @Test
    void 열린_예약은_신청한_달과_무관하게_모두_들어간다() {
        // 11월에 신청해 아직 열린 예약도 12월 노출액에 들어간다(이월 예약, F-1).
        var reservations = List.of(
                new FxExposure.Reservation(FxReservationEntity.Status.OPEN, null, 40_000),
                new FxExposure.Reservation(FxReservationEntity.Status.OPEN, null, 40_000));

        assertEquals(80_000, FxExposure.of(DEC, reservations, List.of()));
    }

    @Test
    void 체결한_예약은_체결한_달에만_들어가고_취소는_빠진다() {
        var reservations = List.of(
                new FxExposure.Reservation(FxReservationEntity.Status.FILLED, LocalDate.of(2026, 12, 3), 40_000),
                new FxExposure.Reservation(FxReservationEntity.Status.FILLED, LocalDate.of(2026, 11, 28), 40_000),
                new FxExposure.Reservation(FxReservationEntity.Status.CANCELLED, null, 40_000));

        assertEquals(40_000, FxExposure.of(DEC, reservations, List.of()));
    }

    @Test
    void 직접매수는_이번_달_완료분만_들어간다() {
        var buys = List.of(
                new FxExposure.Buy(true, LocalDate.of(2026, 12, 16), 20_000),
                new FxExposure.Buy(false, LocalDate.of(2026, 12, 17), 20_000),
                new FxExposure.Buy(true, LocalDate.of(2027, 1, 2), 20_000));

        assertEquals(20_000, FxExposure.of(DEC, List.of(), buys));
    }

    @Test
    void 상한과_같으면_넘지_않고_1원이라도_크면_넘는다() {
        assertFalse(FxExposure.exceedsCap(110_000, 20_000, 130_000));
        assertTrue(FxExposure.exceedsCap(110_001, 20_000, 130_000));
    }
}

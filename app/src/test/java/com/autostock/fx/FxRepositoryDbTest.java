package com.autostock.fx;

import com.autostock.support.PostgresDataJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 PostgreSQL에서 V34 외화 적립 테이블·엔터티·저장소를 확인한다(설계 8장). 컨텍스트가 뜨는 것 자체가
 * 엔터티 ↔ 스키마 일치 검증이다({@link PostgresDataJpaTest}). 값은 합성이다.
 */
class FxRepositoryDbTest extends PostgresDataJpaTest {

    private static final Instant NOW = Instant.parse("2026-12-01T00:10:00Z");

    @Autowired
    FxReservationRepository reservations;

    @Autowired
    FxDirectBuyRepository buys;

    @Autowired
    FxAlertLogRepository alertLog;

    @Autowired
    JdbcTemplate jdbc;

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private FxReservationEntity reserve(FxCurrency currency, LocalDate on, long amount) {
        return reservations.saveAndFlush(new FxReservationEntity(currency, on, d("1360.0000"), d("1330.0000"), amount,
                d("1345.5000"), on.minusDays(1), on.plusMonths(1), NOW));
    }

    @Test
    void 예약을_저장하면_차이가_계산되고_체결_상태가_남는다() {
        FxReservationEntity r = reserve(FxCurrency.USD, LocalDate.of(2026, 12, 1), 40_000);

        assertEquals(0, d("14.5").compareTo(r.getSpread()));
        r.fill(LocalDate.of(2026, 12, 9), d("1330"), NOW);
        reservations.saveAndFlush(r);

        assertEquals("FILLED", jdbc.queryForObject("select status from fx_reservation where id = ?", String.class, r.getId()));
        assertEquals(LocalDate.of(2026, 12, 9), reservations.findById(r.getId()).orElseThrow().getFilledOn());
    }

    @Test
    void ECOS를_못_받은_예약은_참고값과_차이가_비어_저장된다() {
        FxReservationEntity r = reservations.saveAndFlush(new FxReservationEntity(FxCurrency.JPY, LocalDate.of(2026, 12, 1),
                d("905.2"), d("890"), 40_000, null, LocalDate.of(2026, 11, 30), LocalDate.of(2027, 1, 1), NOW));

        assertNull(r.getSpread());
        assertNull(r.getRefObservedOn());
    }

    @Test
    void DB도_예약_환율이_신청_당시보다_낮지_않으면_거절한다() {
        assertThrows(DataIntegrityViolationException.class, () -> reservations.saveAndFlush(new FxReservationEntity(
                FxCurrency.USD, LocalDate.of(2026, 12, 1), d("1330"), d("1330"), 40_000, null, null,
                LocalDate.of(2027, 1, 1), NOW)));
    }

    @Test
    void 기준_예약은_그_통화의_열린_예약_가운데_가장_최근_신청이다() {
        reserve(FxCurrency.USD, LocalDate.of(2026, 11, 3), 40_000);
        FxReservationEntity latest = reserve(FxCurrency.USD, LocalDate.of(2026, 12, 1), 40_000);
        reserve(FxCurrency.JPY, LocalDate.of(2026, 12, 2), 40_000);

        assertEquals(latest.getId(), reservations.findFirstByCurrencyAndStatusOrderByRequestedOnDescIdDesc(
                FxCurrency.USD, FxReservationEntity.Status.OPEN).orElseThrow().getId());
    }

    @Test
    void 노출액_재료_조회는_열린_예약과_그_달_체결만_가져온다() {
        reserve(FxCurrency.USD, LocalDate.of(2026, 11, 3), 40_000);                     // 이월, 열림
        FxReservationEntity filledNov = reserve(FxCurrency.JPY, LocalDate.of(2026, 11, 3), 40_000);
        filledNov.fill(LocalDate.of(2026, 11, 20), d("890"), NOW);
        FxReservationEntity filledDec = reserve(FxCurrency.USD, LocalDate.of(2026, 12, 1), 40_000);
        filledDec.fill(LocalDate.of(2026, 12, 4), d("1330"), NOW);
        reservations.saveAllAndFlush(List.of(filledNov, filledDec));
        buys.saveAndFlush(new FxDirectBuyEntity(FxCurrency.USD, LocalDate.of(2026, 12, 16), d("1338"), 20_000, NOW));

        YearMonth dec = YearMonth.of(2026, 12);
        List<FxReservationEntity> monthReservations = reservations.findByStatusOrFilledOnBetween(
                FxReservationEntity.Status.OPEN, dec.atDay(1), dec.atEndOfMonth());
        List<FxDirectBuyEntity> monthBuys = buys.findByBoughtOnBetweenOrderByBoughtOnAscIdAsc(dec.atDay(1), dec.atEndOfMonth());

        assertEquals(2, monthReservations.size());
        assertEquals(100_000, FxExposure.of(dec, monthReservations.stream().map(FxReservationEntity::exposureView).toList(),
                monthBuys.stream().map(FxDirectBuyEntity::exposureView).toList()));
        assertTrue(buys.existsByCurrencyAndStatusAndBoughtOnBetween(FxCurrency.USD, FxDirectBuyEntity.Status.DONE,
                dec.atDay(1), dec.atEndOfMonth()));
        assertFalse(buys.existsByCurrencyAndStatusAndBoughtOnBetween(FxCurrency.JPY, FxDirectBuyEntity.Status.DONE,
                dec.atDay(1), dec.atEndOfMonth()));
    }

    @Test
    void 같은_종류_통화_기준값의_알림은_두_번_남지_않는다() {
        String key = FxAlertLogEntity.reviewKey(12, LocalDate.of(2026, 12, 15));
        alertLog.saveAndFlush(new FxAlertLogEntity(FxAlertLogEntity.KIND_REVIEW, "USD", key, NOW));

        assertTrue(alertLog.existsByKindAndCurrencyAndAlertKey(FxAlertLogEntity.KIND_REVIEW, "USD", key));
        assertTrue(alertLog.existsByKindAndCurrencyAndSentAtGreaterThanEqual(FxAlertLogEntity.KIND_REVIEW, "USD",
                NOW.minusSeconds(60)));
        assertFalse(alertLog.existsByKindAndCurrencyAndSentAtGreaterThanEqual(FxAlertLogEntity.KIND_REVIEW, "JPY",
                NOW.minusSeconds(60)));
        assertThrows(DataIntegrityViolationException.class, () -> alertLog.saveAndFlush(
                new FxAlertLogEntity(FxAlertLogEntity.KIND_REVIEW, "USD", key, NOW.plusSeconds(5))));
    }
}

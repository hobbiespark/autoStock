package com.autostock.strategy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 미국 발표 밤 판정(E1) — 오늘 ≤ 미국 발표일 < 다음 거래일. */
class UsEventNightsTest {

    private static final String CSV = """
            # 미국 예정 발표일(미국 날짜).
            # 덮는 연도: 2026
            date,kind
            2026-11-06,NFP
            2026-11-10,CPI
            2026-12-09,FOMC
            """;

    private static UsEventNights nights(String csv) {
        return new UsEventNights(true, new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    @Test
    void 그날_밤_발표가_있으면_사건_밤() {
        UsEventNights.Verdict v = nights(CSV).check(d("2026-11-10"), d("2026-11-11"));
        assertTrue(v.eventNight());
        assertEquals(List.of("2026-11-10 CPI"), v.events());
    }

    @Test
    void FOMC는_미국_날짜의_한국_밤_수요일_밤이_사건_밤() {
        // 12/9(수) 14:00 ET = 12/10(목) 04:00 KST — 12/9 종가 → 12/10 시가 사이
        assertTrue(nights(CSV).check(d("2026-12-09"), d("2026-12-10")).eventNight());
        assertFalse(nights(CSV).check(d("2026-12-08"), d("2026-12-09")).eventNight());
        assertFalse(nights(CSV).check(d("2026-12-10"), d("2026-12-11")).eventNight());
    }

    @Test
    void 금요일_밤은_주말까지_포함하고_고용_보고서가_들어간다() {
        UsEventNights.Verdict v = nights(CSV).check(d("2026-11-06"), d("2026-11-09"));
        assertTrue(v.eventNight());
        assertEquals(List.of("2026-11-06 NFP"), v.events());
        assertFalse(nights(CSV).check(d("2026-11-05"), d("2026-11-06")).eventNight(), "목요일 밤은 아니다");
    }

    @Test
    void 일정이_덮지_않는_연도는_막지_않고_덮지_않음으로_알린다() {
        UsEventNights.Verdict v = nights(CSV).check(d("2026-12-31"), d("2027-01-04"));
        assertFalse(v.covered());
        assertFalse(v.eventNight());
    }

    @Test
    void 일정_파일이_없으면_어떤_밤도_덮지_않는다() {
        UsEventNights.Verdict v = new UsEventNights(true, null).check(d("2026-11-10"), d("2026-11-11"));
        assertFalse(v.covered());
    }

    @Test
    void 운영_일정_파일은_올해를_덮고_오늘_밤_CPI를_안다() {
        UsEventNights live = new UsEventNights(true,
                UsEventNights.class.getClassLoader().getResourceAsStream(UsEventNights.RESOURCE));
        UsEventNights.Verdict v = live.check(d("2026-11-10"), d("2026-11-11"));
        assertNotNull(v);
        assertTrue(v.covered());
        assertEquals(List.of("2026-11-10 CPI"), v.events());
    }
}

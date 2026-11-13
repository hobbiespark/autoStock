package com.autostock.fx;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 외화 명령 글 해석(설계 9장). */
class FxCommandTextTest {

    @Test
    void 앱_봇_형식에서_통화_숫자_날짜를_순서대로_뽑는다() {
        FxCommandText.Parsed p = FxCommandText.parse("USD 1330 40,000원 1360.5 2026-12-31");

        assertEquals(FxCurrency.USD, p.currency());
        assertEquals(List.of(new BigDecimal("1330"), new BigDecimal("40000"), new BigDecimal("1360.5")), p.numbers());
        assertEquals(LocalDate.of(2026, 12, 31), p.date());
        assertNull(p.reference());
    }

    @Test
    void 한국어_통화_이름과_번호를_받는다() {
        assertEquals(FxCurrency.JPY, FxCommandText.parse("엔화 890 40000 905").currency());
        assertEquals(FxCurrency.USD, FxCommandText.parse("달러 1338 20000").currency());
        FxCommandText.Parsed ref = FxCommandText.parse("r12 1330");
        assertEquals(new FxCommandText.Reference('R', 12), ref.reference());
        assertEquals(List.of(new BigDecimal("1330")), ref.numbers());
        assertEquals(new FxCommandText.Reference('B', 3), FxCommandText.parse("B3").reference());
    }

    @Test
    void 니보_경유_평문에서도_낱말은_버리고_값만_뽑는다() {
        FxCommandText.Parsed p = FxCommandText.parse("달러 예약 1330 에 40000 원 신청 당시 1360 기록해줘");

        assertEquals(FxCurrency.USD, p.currency());
        assertEquals(List.of(new BigDecimal("1330"), new BigDecimal("40000"), new BigDecimal("1360")), p.numbers());
    }

    @Test
    void 빈_글과_없는_날짜는_비어_있다() {
        FxCommandText.Parsed empty = FxCommandText.parse(null);
        assertNull(empty.currency());
        assertTrue(empty.numbers().isEmpty());
        assertNull(FxCommandText.parse("2026-02-30").date());
    }
}

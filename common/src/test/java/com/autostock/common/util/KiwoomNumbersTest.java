package com.autostock.common.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * KiwoomNumbers 단위테스트 — 부호 접두("+13500"/"-13500") 정규화, 빈 값·null 방어를 검증한다.
 */
class KiwoomNumbersTest {

    @Test
    void 양수_부호_접두는_제거된다() {
        assertEquals("13500", KiwoomNumbers.stripSign("+13500"));
        assertEquals(new BigDecimal("13500"), KiwoomNumbers.toBigDecimal("+13500"));
        assertEquals(13500L, KiwoomNumbers.toLong("+13500"));
    }

    @Test
    void 음수_부호_접두는_제거되고_값은_절대값으로_취급된다() {
        assertEquals("13500", KiwoomNumbers.stripSign("-13500"));
        assertEquals(new BigDecimal("13500"), KiwoomNumbers.toBigDecimal("-13500"));
        assertEquals(13500L, KiwoomNumbers.toLong("-13500"));
    }

    @Test
    void 부호_없는_값은_그대로_유지된다() {
        assertEquals("13500", KiwoomNumbers.stripSign("13500"));
        assertEquals(new BigDecimal("13500"), KiwoomNumbers.toBigDecimal("13500"));
        assertEquals(13500L, KiwoomNumbers.toLong("13500"));
    }

    @Test
    void 빈값이나_null은_0으로_취급된다() {
        assertEquals("", KiwoomNumbers.stripSign(null));
        assertEquals("", KiwoomNumbers.stripSign(""));
        assertEquals(BigDecimal.ZERO, KiwoomNumbers.toBigDecimal(null));
        assertEquals(BigDecimal.ZERO, KiwoomNumbers.toBigDecimal(""));
        assertEquals(0L, KiwoomNumbers.toLong(null));
        assertEquals(0L, KiwoomNumbers.toLong(""));
    }

    @Test
    void 숫자가_아닌_값은_toLongOrZero에서_예외_대신_0을_반환한다() {
        assertEquals(0L, KiwoomNumbers.toLongOrZero("체결"));
        assertEquals(0L, KiwoomNumbers.toLongOrZero(null));
        assertEquals(10L, KiwoomNumbers.toLongOrZero("+10"));
    }

    @Test
    void 숫자가_아닌_값은_toLong에서_예외를_던진다() {
        org.junit.jupiter.api.Assertions.assertThrows(NumberFormatException.class,
                () -> KiwoomNumbers.toLong("체결"));
    }

    @Test
    void Object_타입_숫자도_정상_처리된다() {
        Object raw = Integer.valueOf(70100);
        assertEquals("70100", KiwoomNumbers.stripSign(raw));
        assertEquals(new BigDecimal("70100"), KiwoomNumbers.toBigDecimal(raw));
    }
}

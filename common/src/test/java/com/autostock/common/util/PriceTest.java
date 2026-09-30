package com.autostock.common.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Price 생성 시 검증 경계값과 수치 기준 같음. */
class PriceTest {

    @Test
    void 가격은_양수만_허용하고_0_음수_null은_거부한다() {
        assertThrows(IllegalArgumentException.class, () -> new Price(null));
        assertThrows(IllegalArgumentException.class, () -> new Price(BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new Price(new BigDecimal("-1")));
        assertEquals(new BigDecimal("0.01"), new Price(new BigDecimal("0.01")).value());
    }

    @Test
    void 자릿수가_달라도_수치가_같으면_같은_가격이다() {
        Price plain = new Price(new BigDecimal("259000"));
        Price scaled = new Price(new BigDecimal("259000.00"));

        assertEquals(plain, scaled);
        assertEquals(plain.hashCode(), scaled.hashCode());
        assertNotEquals(plain, new Price(new BigDecimal("259500")));
    }

    @Test
    void 문자열은_지수_표기_없이_받은_값_그대로다() {
        assertEquals("259000", new Price(new BigDecimal("2.59E+5")).toString());
        assertEquals("1889000.50", new Price(new BigDecimal("1889000.50")).toString());
    }
}

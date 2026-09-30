package com.autostock.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** StockCode 생성 시 검증 경계값. */
class StockCodeTest {

    @Test
    void 영숫자_대문자_6자리만_허용한다() {
        assertEquals("005930", new StockCode("005930").value());
        assertEquals("0126Z0", new StockCode("0126Z0").value()); // 2024년 이후 영문 포함 코드
    }

    @Test
    void 자릿수_소문자_접두사_공백은_거부한다() {
        assertThrows(IllegalArgumentException.class, () -> new StockCode(null));
        assertThrows(IllegalArgumentException.class, () -> new StockCode("5930"));
        assertThrows(IllegalArgumentException.class, () -> new StockCode("0059300"));
        assertThrows(IllegalArgumentException.class, () -> new StockCode("A005930")); // 키움 접두사는 어댑터가 벗긴다
        assertThrows(IllegalArgumentException.class, () -> new StockCode("0126z0"));
        assertThrows(IllegalArgumentException.class, () -> new StockCode(" 005930"));
    }

    @Test
    void 문자열은_값_그대로다() {
        assertEquals("005930", new StockCode("005930").toString());
    }
}

package com.autostock.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MomentumMath 검증 — 수작업으로 계산한 값과 비교한다.
 */
class MomentumMathTest {

    private List<BigDecimal> closes(String... values) {
        return List.of(values).stream().map(BigDecimal::new).toList();
    }

    @Test
    void 어제_종가가_N봉_전_종가보다_높으면_상승추세() {
        // lookback=3 → 마지막(어제)=10500, 마지막에서 4번째(N봉전)=10000
        List<BigDecimal> closes = closes("10000", "10100", "10200", "10500");
        assertTrue(MomentumMath.shouldHold(closes, 3), "어제(10500) > N봉전(10000)이므로 상승추세여야 함");
    }

    @Test
    void 어제_종가가_N봉_전_종가보다_낮으면_하락추세() {
        List<BigDecimal> closes = closes("10000", "9800", "9700", "9500");
        assertFalse(MomentumMath.shouldHold(closes, 3), "어제(9500) < N봉전(10000)이므로 하락추세여야 함");
    }

    @Test
    void 어제_종가와_N봉_전_종가가_같으면_상승추세가_아니다() {
        // compareTo > 0만 상승추세이므로, 같으면(0) false여야 함(엄격한 부등호)
        List<BigDecimal> closes = closes("10000", "10100", "10200", "10000");
        assertFalse(MomentumMath.shouldHold(closes, 3), "동률(같음)은 상승추세가 아니어야 함");
    }

    @Test
    void 데이터가_부족하면_예외() {
        List<BigDecimal> closes = closes("10000", "10100"); // lookback=3이면 4개 필요
        assertThrows(IllegalArgumentException.class, () -> MomentumMath.shouldHold(closes, 3));
    }

    @Test
    void lookbackN이_0이하이면_예외() {
        List<BigDecimal> closes = closes("10000", "10100");
        assertThrows(IllegalArgumentException.class, () -> MomentumMath.shouldHold(closes, 0));
    }

    @Test
    void 목록_맨_앞의_추가_과거_데이터는_판단에_영향을_주지_않는다() {
        // 항상 목록의 "끝"을 기준으로 인덱싱하므로, 앞쪽에 더 오래된 데이터가 남아 있어도 무방하다.
        List<BigDecimal> closes = closes("99999", "88888", "10000", "10100", "10200", "10500");
        assertTrue(MomentumMath.shouldHold(closes, 3), "맨 앞 여분 데이터와 무관하게 어제(10500)>N봉전(10000)이면 상승추세");
    }
}

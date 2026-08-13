package com.autostock.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BreakoutMath 검증 — 수작업으로 계산한 값과 비교한다.
 */
class BreakoutMathTest {

    @Test
    void 목표가는_오늘시가_플러스_k곱하기_전일변동폭이다() {
        // target = 10000 + 0.5 * (10500 - 10200) = 10000 + 150 = 10150
        BigDecimal target = BreakoutMath.target(
                new BigDecimal("10000"), new BigDecimal("10500"), new BigDecimal("10200"), 0.5);
        assertEquals(new BigDecimal("10150.0"), target);
    }

    @Test
    void k가_0이면_목표가는_오늘시가와_같다() {
        BigDecimal target = BreakoutMath.target(
                new BigDecimal("10000"), new BigDecimal("11000"), new BigDecimal("9000"), 0.0);
        assertEquals(new BigDecimal("10000.0"), target);
    }

    @Test
    void k가_1이면_목표가는_시가_플러스_전일변동폭_전체다() {
        // target = 5000 + 1.0 * (5300 - 5100) = 5000 + 200 = 5200
        BigDecimal target = BreakoutMath.target(
                new BigDecimal("5000"), new BigDecimal("5300"), new BigDecimal("5100"), 1.0);
        assertEquals(new BigDecimal("5200.0"), target);
    }

    @Test
    void 전일변동폭이_0이면_목표가는_오늘시가와_같다() {
        BigDecimal target = BreakoutMath.target(
                new BigDecimal("7000"), new BigDecimal("7100"), new BigDecimal("7100"), 0.5);
        assertEquals(new BigDecimal("7000.0"), target);
    }
}

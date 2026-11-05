package com.autostock.ipo;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 공모주 상장일 실거래 매도 계산(2026-11-05 사용자 확정 규칙, aiDoc/ipo-automation.md 8절) — 규칙 값을 고정한다. */
class IpoSellMathTest {

    @Test
    void 규칙_값을_고정한다() {
        assertArrayEquals(new int[]{50, 30, 20}, IpoSellMath.PERCENTS);
        assertEquals(0, new BigDecimal("0.60").compareTo(IpoSellMath.FLOOR_RATIO));
        assertEquals(0, new BigDecimal("0.05").compareTo(IpoSellMath.SECOND_DROP));
        assertEquals(0, new BigDecimal("0.10").compareTo(IpoSellMath.THIRD_DROP));
        assertEquals(0, new BigDecimal("0.99").compareTo(IpoSellMath.INTRADAY_LIMIT_RATIO));
    }

    @Test
    void 수량을_50_30_20으로_나누고_남는_주는_1차에_몬다() {
        assertArrayEquals(new int[]{1, 0, 0}, IpoSellMath.split(1), "1주는 1차 전량");
        assertArrayEquals(new int[]{2, 0, 0}, IpoSellMath.split(2), "2주는 1차 전량");
        assertArrayEquals(new int[]{3, 0, 0}, IpoSellMath.split(3), "3주 — 30%·20%가 1주 미만이라 1차 전량");
        assertArrayEquals(new int[]{5, 3, 2}, IpoSellMath.split(10));
        assertArrayEquals(new int[]{6, 3, 2}, IpoSellMath.split(11), "11주 — 내림으로 남는 1주는 1차");
        assertArrayEquals(new int[]{0, 0, 0}, IpoSellMath.split(0));
        assertArrayEquals(new int[]{0, 0, 0}, IpoSellMath.split(-3));
    }

    @Test
    void 일차_지정가는_공모가_60퍼센트를_호가단위로_올린다() {
        assertEquals(new BigDecimal("9900"), IpoSellMath.floorPrice(new BigDecimal("16500")), "16,500×0.6=9,900(10원 단위)");
        assertEquals(new BigDecimal("20000"), IpoSellMath.floorPrice(new BigDecimal("33333")), "19,999.8 → 올림 20,000");
        assertEquals(new BigDecimal("2005"), IpoSellMath.floorPrice(new BigDecimal("3335")), "2,001 → 5원 단위 올림 2,005");
        assertEquals(new BigDecimal("1200"), IpoSellMath.floorPrice(new BigDecimal("2000")), "1,200(1원 단위)");
        assertEquals(new BigDecimal("30050"), IpoSellMath.floorPrice(new BigDecimal("50001")), "30,000.6 → 50원 단위 올림");
    }

    @Test
    void 장중_지정가는_체결가_99퍼센트를_내리고_하한_아래로_가지_않는다() {
        assertEquals(new BigDecimal("19750"), IpoSellMath.intradayLimit(new BigDecimal("19950"), new BigDecimal("9900")),
                "19,750.5 → 10원 단위 내림 19,750");
        assertEquals(new BigDecimal("19800"), IpoSellMath.intradayLimit(new BigDecimal("20000"), new BigDecimal("9900")),
                "19,800 — 2만 원 미만은 10원 단위");
        assertEquals(new BigDecimal("9900"), IpoSellMath.intradayLimit(new BigDecimal("9950"), new BigDecimal("9900")),
                "9,850.5 → 하한 9,900");
    }

    @Test
    void 고점_대비_하락을_판정한다() {
        BigDecimal high = new BigDecimal("21000");
        assertTrue(IpoSellMath.droppedFromHigh(high, new BigDecimal("19950"), IpoSellMath.SECOND_DROP), "정확히 −5%");
        assertFalse(IpoSellMath.droppedFromHigh(high, new BigDecimal("19960"), IpoSellMath.SECOND_DROP));
        assertTrue(IpoSellMath.droppedFromHigh(high, new BigDecimal("18900"), IpoSellMath.THIRD_DROP), "정확히 −10%");
        assertFalse(IpoSellMath.droppedFromHigh(high, new BigDecimal("18910"), IpoSellMath.THIRD_DROP));
        assertFalse(IpoSellMath.droppedFromHigh(null, new BigDecimal("1"), IpoSellMath.SECOND_DROP));
    }
}

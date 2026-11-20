package com.autostock.risk;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 전략별 1주 상한(VALUNIVO 917) — RiskGate의 최소 유의미 수량 상한과 같은 식인지. */
class OneShareCapTest {

    /** 운영 값(2026-10-30 결정): 운용 자금 200만, 1주 상한 배수 2.0, 유의미 기준 500원·1%. */
    private static final TradingCapitalProperties CAPITAL = new TradingCapitalProperties(2_000_000, 2.0, 500, 0.01);
    /** 운영 슬리브(application.yml)에서 세 전략. */
    private static final MultiStrategyRiskProperties SLEEVES = new MultiStrategyRiskProperties(true, List.of(
            new MultiStrategyRiskProperties.Sleeve("VB-DAY", 0.15, 3),
            new MultiStrategyRiskProperties.Sleeve("ON-CLOSE", 0.15, 3),
            new MultiStrategyRiskProperties.Sleeve("TA-H1", 0.04, 5)));

    private static OneShareCap cap(BigDecimal accountEquity) {
        return new OneShareCap(SLEEVES, CAPITAL, () -> accountEquity);
    }

    @Test
    void 운영_값이면_단타는_20만_원_차트_기법은_10만_원() {
        OneShareCap c = cap(new BigDecimal("484138536"));   // 모의 계좌 평가액(11-20) — 사이징 기준은 운용 자금 200만
        // 단타: 200만 × 15% ÷ 3 = 10만 → × 2 = 20만
        assertEquals(0, new BigDecimal("200000").compareTo(c.capFor("VB-DAY").orElseThrow()));
        assertEquals(0, new BigDecimal("200000").compareTo(c.capFor("ON-CLOSE").orElseThrow()));
        // 차트 기법: 200만 × 4% ÷ 5 = 1.6만 → × 2 = 3.2만 < 하한 2 × (500 ÷ 1%) = 10만
        assertEquals(0, new BigDecimal("100000").compareTo(c.capFor("TA-H1").orElseThrow()));
    }

    @Test
    void 상한을_넘을_때만_거른다() {
        OneShareCap c = cap(new BigDecimal("484138536"));
        assertTrue(c.exceeds("VB-DAY", new BigDecimal("215000")), "셀트리온 11-20 — 관문이 거부한 값");
        assertFalse(c.exceeds("VB-DAY", new BigDecimal("200000")), "같으면 관문이 받는다");
        assertFalse(c.exceeds("VB-DAY", new BigDecimal("123100")));
    }

    @Test
    void 모르면_거르지_않는다() {
        assertFalse(cap(null).exceeds("VB-DAY", new BigDecimal("999999999")), "평가액 모름");
        assertFalse(cap(new BigDecimal("484138536")).exceeds("KODEX-HOLD", new BigDecimal("999999999")), "슬리브 없음");
        OneShareCap off = new OneShareCap(SLEEVES, new TradingCapitalProperties(2_000_000, 2.0, 0, 0.01),
                () -> new BigDecimal("484138536"));
        assertTrue(off.capFor("VB-DAY").isEmpty(), "최소 유의미 수량 규칙이 꺼지면 상한 없음");
    }
}

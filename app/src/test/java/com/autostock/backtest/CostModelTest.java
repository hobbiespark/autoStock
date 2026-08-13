package com.autostock.backtest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CostModel 검증 — 기본값(매수/매도 수수료 각 0.015%, 매도세 0.15%, 슬리피지 0.05%)에 대해
 * 구체적인 숫자로 비용이 정확히 계산되는지 확인한다.
 */
class CostModelTest {

    private final CostModel costModel = CostModel.defaults();

    @Test
    void 매수_슬리피지는_기준가보다_높게_체결된다() {
        // 10000 * (1 + 0.0005) = 10005
        BigDecimal price = costModel.slippageAdjustedBuyPrice(new BigDecimal("10000"));
        assertEquals(new BigDecimal("10005.00000"), price);
    }

    @Test
    void 매도_슬리피지는_기준가보다_낮게_체결된다() {
        // 10000 * (1 - 0.0005) = 9995
        BigDecimal price = costModel.slippageAdjustedSellPrice(new BigDecimal("10000"));
        assertEquals(new BigDecimal("9995.00000"), price);
    }

    @Test
    void 매수_수수료는_체결금액의_0_015퍼센트() {
        // 1,000,000 * 0.00015 = 150
        BigDecimal fee = costModel.buyFee(new BigDecimal("1000000"));
        assertEquals(new BigDecimal("150.00000"), fee);
    }

    @Test
    void 매도_비용은_수수료_0_015퍼센트_플러스_거래세_0_15퍼센트() {
        // 1,000,000 * (0.00015 + 0.0015) = 1,000,000 * 0.00165 = 1650
        BigDecimal fee = costModel.sellFee(new BigDecimal("1000000"));
        assertEquals(new BigDecimal("1650.00000"), fee);
    }

    @Test
    void 커스텀_비용모델_생성자_주입() {
        CostModel custom = new CostModel(0.001, 0.001, 0.002, 0.001);
        // 10000 * (1+0.001) = 10010
        assertEquals(new BigDecimal("10010.000"), custom.slippageAdjustedBuyPrice(new BigDecimal("10000")));
        // 100000 * (0.001+0.002) = 300
        assertEquals(new BigDecimal("300.000"), custom.sellFee(new BigDecimal("100000")));
    }
}

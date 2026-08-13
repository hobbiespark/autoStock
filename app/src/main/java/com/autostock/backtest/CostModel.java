package com.autostock.backtest;

import java.math.BigDecimal;

/**
 * 백테스트 체결 비용 모델 — 수수료·세금·슬리피지를 얼마로 볼 것인가.
 *
 * <p><b>단기 전략은 비용 모델의 정확도가 성패를 결정한다.</b> 수익률이 하루에
 * 0.1~0.3%인 전략에서 비용을 0.1%p만 낙관적으로 잡아도 백테스트 성과가 통째로
 * 허수가 된다. 그래서 비용을 코드에 하드코딩하지 않고 이 클래스로 분리해
 * 종목·시기별로 다른 값을 실험해볼 수 있게 했다.
 *
 * <p>구성 요소:
 * <ul>
 *   <li>매수 수수료 {@link #buyFeePct}: 매수 체결 금액(notional)에 곱한다.</li>
 *   <li>매도 수수료 {@link #sellFeePct}: 매도 체결 금액에 곱한다.</li>
 *   <li>증권거래세 {@link #sellTaxPct}: 매도에만 붙는다(매수에는 없음).</li>
 *   <li>슬리피지 {@link #slippagePct}: "주문한 가격 그대로 체결된다"는 가정을 깨는 값.
 *       실제로는 내 주문이 호가창을 밀어내며 체결되므로, 매수는 더 비싸게,
 *       매도는 더 싸게 체결된 것으로 본다(항상 나에게 불리한 방향).</li>
 * </ul>
 *
 * <p>기본값(국내 주식 위탁 기준 근사치): 매수/매도 수수료 각 0.015%,
 * 매도 시 증권거래세 0.15%, 슬리피지 0.05%.
 *
 * @param buyFeePct   매수 수수료율 (예: 0.00015 = 0.015%)
 * @param sellFeePct  매도 수수료율
 * @param sellTaxPct  매도 시 증권거래세율
 * @param slippagePct 슬리피지율 (체결가를 불리한 방향으로 조정하는 비율)
 */
public record CostModel(
        double buyFeePct,
        double sellFeePct,
        double sellTaxPct,
        double slippagePct
) {

    /** 기본 비용 모델 — PLAN 스펙에 명시된 기본값. */
    public static CostModel defaults() {
        return new CostModel(0.00015, 0.00015, 0.0015, 0.0005);
    }

    /**
     * 매수 체결가 = 기준가(호가/시가) × (1 + 슬리피지율).
     * 매수는 항상 기준가보다 "더 비싸게" 체결된 것으로 본다.
     */
    public BigDecimal slippageAdjustedBuyPrice(BigDecimal referencePrice) {
        return referencePrice.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(slippagePct)));
    }

    /**
     * 매도 체결가 = 기준가 × (1 − 슬리피지율).
     * 매도는 항상 기준가보다 "더 싸게" 체결된 것으로 본다.
     */
    public BigDecimal slippageAdjustedSellPrice(BigDecimal referencePrice) {
        return referencePrice.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(slippagePct)));
    }

    /** 매수 수수료 금액 = 체결 금액(notional) × 매수 수수료율. */
    public BigDecimal buyFee(BigDecimal notional) {
        return notional.multiply(BigDecimal.valueOf(buyFeePct));
    }

    /** 매도 비용 총액(수수료+거래세) = 체결 금액 × (매도 수수료율 + 거래세율). */
    public BigDecimal sellFee(BigDecimal notional) {
        return notional.multiply(BigDecimal.valueOf(sellFeePct + sellTaxPct));
    }
}

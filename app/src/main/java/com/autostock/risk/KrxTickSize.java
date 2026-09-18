package com.autostock.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * KRX 호가단위(tick size) 정규화 — 지정가를 거래소가 받는 가격 눈금에 맞춘다.
 *
 * <p>존재 이유(실측 2026-09-18, mockapi): 삼성전자 259,250원 매도 지정가가
 * {@code RC4003: 모의투자 호가단위 오류입니다}로 즉시 거부됐다(20만 원 이상은 500원 단위).
 * 수동 폼뿐 아니라 전략이 계산한 기준가(이동평균·변동성 기반 값)도 눈금에 맞지 않을 수 있으므로,
 * 모든 주문이 지나는 {@link RiskGate}에서 한 번 정규화한다. 브로커 거부 후 재시도하는 것보다
 * 주문 전에 맞추는 편이 일 주문 한도 슬롯·로그·대사 부담이 없다.
 *
 * <p>눈금표(코스피·코스닥 공통, 2023-01-25 개편 이후):
 * <pre>
 *   ~2,000 미만        1원
 *   ~5,000 미만        5원
 *   ~20,000 미만      10원
 *   ~50,000 미만      50원
 *   ~200,000 미만    100원
 *   ~500,000 미만    500원
 *   500,000 이상   1,000원
 * </pre>
 * 반올림은 최근접(HALF_UP)이다 — 의도한 가격에서 최대 반 눈금만 벗어나며, 매수/매도 어느 쪽도
 * 체계적으로 불리해지지 않는다. 눈금 경계(예: 199,950→200,000)를 넘는 경우도 결과값이 다시
 * 눈금에 맞는지 확인해 한 번 더 정렬한다.
 */
public final class KrxTickSize {

    private KrxTickSize() {
    }

    /** 가격대별 호가단위. */
    public static BigDecimal tickFor(BigDecimal price) {
        long p = price.longValue();
        if (p < 2_000) return BigDecimal.ONE;
        if (p < 5_000) return BigDecimal.valueOf(5);
        if (p < 20_000) return BigDecimal.valueOf(10);
        if (p < 50_000) return BigDecimal.valueOf(50);
        if (p < 200_000) return BigDecimal.valueOf(100);
        if (p < 500_000) return BigDecimal.valueOf(500);
        return BigDecimal.valueOf(1_000);
    }

    /** 가격이 호가단위 눈금에 정확히 맞는지. */
    public static boolean isAligned(BigDecimal price) {
        if (price == null || price.signum() <= 0) return false;
        return price.remainder(tickFor(price)).signum() == 0
                && price.stripTrailingZeros().scale() <= 0;
    }

    /**
     * 가격을 가장 가까운 호가단위 눈금으로 맞춘다. null·0 이하는 그대로 돌려준다(시장가 등 호출자 책임).
     */
    public static BigDecimal align(BigDecimal price) {
        if (price == null || price.signum() <= 0) return price;
        BigDecimal aligned = alignOnce(price);
        // 경계 통과(예: 199,950 → 200,000)로 눈금이 바뀌면 새 눈금으로 한 번 더 정렬한다.
        if (!isAligned(aligned)) aligned = alignOnce(aligned);
        return aligned;
    }

    private static BigDecimal alignOnce(BigDecimal price) {
        BigDecimal tick = tickFor(price);
        return price.divide(tick, 0, RoundingMode.HALF_UP).multiply(tick).setScale(0, RoundingMode.UNNECESSARY);
    }
}

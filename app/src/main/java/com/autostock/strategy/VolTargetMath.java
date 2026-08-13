package com.autostock.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 변동성 타게팅(volatility targeting) — 최근 실현 변동성에 반비례해 투입 비중(fraction)을
 * 계산하는 순수 함수 하나짜리 클래스.
 *
 * <p>원래 {@code backtest.VolatilityTargetingStrategy} 안에 있던 계산이었으나, 다른
 * *Math 클래스들과 같은 이유(백테스트=라이브 동형)로 이 클래스로 옮겼다. 백테스트 쪽
 * 데코레이터는 이 메서드를 호출해 {@code TradeIntent.fraction()}을 계산하고, 라이브
 * ({@code strategy.C3LiveStrategy})은 같은 메서드로 {@code Signal.confidence()}를 계산한다.
 *
 * <h2>계산식</h2>
 * <pre>
 *   realizedVol = stdDev(최근 20봉 일간수익률) × sqrt(252)   (연환산 실현 변동성)
 *   fraction    = min(1.0, targetVolAnnual / realizedVol)
 * </pre>
 * 일간수익률은 종가 기준 전일 대비 변화율이다. 관측치가 20개 미만이면 판단 근거 부족으로
 * 보수적이지 않고(=제한하지 않고) fraction=1.0을 그대로 쓴다.
 *
 * <h2>룩어헤드 방지</h2>
 * 입력으로 받는 종가 목록은 반드시 "오늘 판단 시점 이전"에 이미 확정된 값들이어야 한다.
 * 이 메서드 자체는 목록의 마지막 21개만 사용하므로, 호출자가 오늘 종가를 섞어 넣지 않도록
 * 주의해야 한다(TimeSeriesMomentumStrategy류와 동일한 책임 분담).
 */
public final class VolTargetMath {

    /** 실현 변동성 계산에 쓰는 일간수익률 관측 창(봉 수). */
    public static final int VOL_WINDOW = 20;

    /** 연환산 거래일수 — PerformanceCalculator와 동일한 관례. */
    private static final int TRADING_DAYS_PER_YEAR = 252;

    private VolTargetMath() {
        // 정적 유틸리티 — 인스턴스화 금지
    }

    /**
     * @param closes          시간순(오름차순)으로 정렬된 확정 종가 목록. 마지막 원소가
     *                        "오늘 판단 시점의 어제 종가". 최소 (VOL_WINDOW+1)=21개 필요.
     *                        그보다 적으면 관측 부족으로 1.0을 반환한다.
     * @param targetVolAnnual 목표 연변동성(예: 0.20 = 연 20%)
     * @return 투입 비중, (0, 1] 구간으로 cap
     */
    public static double fraction(List<BigDecimal> closes, double targetVolAnnual) {
        if (closes == null || closes.size() < VOL_WINDOW + 1) {
            return 1.0; // 관측 부족 — 제한하지 않고 전액 투입 신뢰
        }

        List<BigDecimal> recent = new ArrayList<>(
                closes.subList(closes.size() - (VOL_WINDOW + 1), closes.size()));

        double[] dailyReturns = new double[VOL_WINDOW];
        for (int i = 1; i < recent.size(); i++) {
            BigDecimal prev = recent.get(i - 1);
            BigDecimal curr = recent.get(i);
            dailyReturns[i - 1] = prev.signum() == 0
                    ? 0.0
                    : curr.subtract(prev).divide(prev, 12, RoundingMode.HALF_UP).doubleValue();
        }

        double mean = mean(dailyReturns);
        double std = populationStd(dailyReturns, mean);
        double realizedVol = std * Math.sqrt(TRADING_DAYS_PER_YEAR);
        if (realizedVol == 0.0) {
            return 1.0; // 최근 변동이 전혀 없었으면 제한할 이유가 없음
        }
        return Math.min(1.0, targetVolAnnual / realizedVol);
    }

    private static double mean(double[] values) {
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return values.length == 0 ? 0.0 : sum / values.length;
    }

    /** 모표준편차(분모 n) — PerformanceCalculator와 동일한 관례. */
    private static double populationStd(double[] values, double mean) {
        double sumSq = 0.0;
        for (double v : values) {
            double d = v - mean;
            sumSq += d * d;
        }
        return Math.sqrt(sumSq / values.length);
    }
}

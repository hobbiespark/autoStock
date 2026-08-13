package com.autostock.backtest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PerformanceCalculator 검증.
 * 기대값은 동일한 수식을 파이썬으로 별도 재현해 손으로 계산한 값이다(erf/역정규CDF
 * 근사식도 동일하게 재현해 부동소수 오차 범위 내에서 일치하는지 확인했다).
 */
class PerformanceCalculatorTest {

    // mean=0.0083333333, population std=0.0177169097 (수작업/파이썬 검증값)
    private static final List<Double> RETURNS = List.of(0.02, -0.01, 0.03, 0.01, -0.02, 0.02);

    private final PerformanceCalculator calculator = new PerformanceCalculator();

    @Test
    void 샤프비율_연율화_검증() {
        // sharpe_daily = 0.008333333/0.017716910 = 0.4703604...
        // sharpe_annual = sharpe_daily * sqrt(252) = 7.4667404...
        double sharpe = calculator.sharpe(RETURNS);
        assertEquals(7.466740412615168, sharpe, 1e-9);
    }

    @Test
    void 최대낙폭_MDD_검증() {
        // 누적자산: 1.02, 1.0098, 1.040094, 1.0504949..., 1.02948..., 1.0500758...
        // 고점(1.0504949...) 대비 최대 하락폭(1.02948... 지점) = 0.02
        double mdd = calculator.mdd(RETURNS);
        assertEquals(0.020000000000000035, mdd, 1e-9);
    }

    @Test
    void DSR_시도횟수1이면_SR기반_CDF와_일치() {
        // trials=1 → SR0=0 → DSR = Φ(SR_obs*sqrt(T-1)/denom). 파이썬 재현값: 0.8257186878197822
        double srDaily = 0.4703604341917986;
        double skew = -0.4661979524732871;
        double kurt = 1.7167358446236978;
        double dsr = calculator.deflatedSharpeRatio(srDaily, 1, 0.0, skew, kurt, RETURNS.size());
        assertEquals(0.8257186878197822, dsr, 1e-9);
    }

    @Test
    void DSR_시도횟수가_늘면_신뢰수준이_감소한다() {
        double srDaily = 0.4703604341917986;
        double skew = -0.4661979524732871;
        double kurt = 1.7167358446236978;
        int t = RETURNS.size();

        double dsrTrials1 = calculator.deflatedSharpeRatio(srDaily, 1, 0.0, skew, kurt, t);
        double dsrTrials10 = calculator.deflatedSharpeRatio(srDaily, 10, 0.01, skew, kurt, t);
        double dsrTrials100 = calculator.deflatedSharpeRatio(srDaily, 100, 0.01, skew, kurt, t);

        // 시도(N)를 많이 할수록 "운으로 나온 최댓값"과 구분하기 어려워지므로 신뢰수준은 떨어져야 한다.
        assertTrue(dsrTrials1 > dsrTrials10, "N=1 > N=10 이어야 함");
        assertTrue(dsrTrials10 > dsrTrials100, "N=10 > N=100 이어야 함");

        assertEquals(0.733548118531231, dsrTrials10, 1e-9);
        assertEquals(0.6675133861082786, dsrTrials100, 1e-9);
    }

    @Test
    void calculate_전체_결과조립_확인() {
        BigDecimal initial = new BigDecimal("1000000");
        BigDecimal finalEquity = new BigDecimal("1050000"); // 총수익률 5%
        BacktestResult result = calculator.calculate(RETURNS, initial, finalEquity, 3, 1, 0.0);

        assertEquals(0.05, result.totalReturn(), 1e-9);
        assertEquals(6, result.dailyReturns().size());
        assertEquals(3, result.tradeCount());
        assertEquals(7.466740412615168, result.sharpe(), 1e-9);
        assertEquals(0.020000000000000035, result.mdd(), 1e-9);
    }
}

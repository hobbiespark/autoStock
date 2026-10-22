package com.autostock.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 단타 변동성 돌파(VB-DAY) 판단식 — 순수 함수(2026-10-16, aiDoc/multi-strategy-paper-2026-10-16.md 3-1).
 *
 * <p>진입은 실험 2의 B안({@code backtest.FilteredBreakoutStrategy})과 같다: 오늘 시가 &gt; 직전 20봉 종가 평균,
 * 전일 양봉, 목표가 = 시가 + k × 전일 변동폭({@link BreakoutMath#target}). 청산은 ADR-7(익절·손절 가격 기준)이다.
 * 계산 코어 규칙(ArchitectureRulesTest)에 따라 자바 기본 타입만 쓴다 — 일봉에서 값을 꺼내는 일은 전략(셸)이 한다.
 */
public final class DayBreakoutMath {

    private DayBreakoutMath() {
    }

    /**
     * 장전 준비값 — 완료된 일봉(오늘 이전)에서 센다.
     *
     * @param smaClose 직전 smaDays봉 종가 평균(전일 포함)
     */
    public record Setup(BigDecimal prevOpen, BigDecimal prevHigh, BigDecimal prevLow, BigDecimal prevClose,
                        BigDecimal smaClose) {

        /** 전일 양봉(종가 &gt; 시가). */
        public boolean prevUp() {
            return prevClose.compareTo(prevOpen) > 0;
        }
    }

    /**
     * @param closes 완료된 종가(오름차순, 마지막 = 전일 종가)
     * @return 준비값 — 종가가 smaDays개보다 적으면 null(데이터 부족)
     */
    public static Setup setup(List<BigDecimal> closes, BigDecimal prevOpen, BigDecimal prevHigh, BigDecimal prevLow,
                              int smaDays) {
        if (closes == null || closes.size() < smaDays || smaDays <= 0) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = closes.size() - smaDays; i < closes.size(); i++) {
            sum = sum.add(closes.get(i));
        }
        BigDecimal sma = sum.divide(BigDecimal.valueOf(smaDays), 10, RoundingMode.HALF_UP);
        return new Setup(prevOpen, prevHigh, prevLow, closes.get(closes.size() - 1), sma);
    }

    /** 오늘 시가로 필터를 통과하나 — 전일 양봉이고 시가 &gt; SMA. */
    public static boolean passesFilters(Setup setup, BigDecimal todayOpen) {
        return setup != null && todayOpen != null && setup.prevUp() && todayOpen.compareTo(setup.smaClose()) > 0;
    }

    /** 목표가 = 시가 + k × (전일 고가 − 전일 저가). */
    public static BigDecimal target(Setup setup, BigDecimal todayOpen, double k) {
        return BreakoutMath.target(todayOpen, setup.prevHigh(), setup.prevLow(), k);
    }

    /** 익절 — 가격 ≥ 평단 × (1 + takeProfit). */
    public static boolean hitsTakeProfit(BigDecimal price, BigDecimal avgPrice, double takeProfit) {
        return avgPrice != null && price.compareTo(avgPrice.multiply(BigDecimal.valueOf(1 + takeProfit))) >= 0;
    }

    /** 손절 — 가격 ≤ 평단 × (1 − stopLoss). stopLoss는 양수 크기(0.015 = −1.5%). */
    public static boolean hitsStopLoss(BigDecimal price, BigDecimal avgPrice, double stopLoss) {
        return avgPrice != null && price.compareTo(avgPrice.multiply(BigDecimal.valueOf(1 - stopLoss))) <= 0;
    }
}

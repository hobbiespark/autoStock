package com.autostock.backtest;

import java.time.LocalDate;
import java.util.List;

/**
 * 트랙 Z 지수 타이밍 규칙(docs/research/trackz_preregistration_20261114.md 2장) — 날 i의 KODEX200 비중.
 * 모두 i−1 종가까지의 가격과 거래소 달력만 쓴다. 규칙은 실행 전에 고정했다.
 */
public final class IndexTimingRules {

    public static final int LONG_VAR_WINDOW = 252;
    public static final int SHORT_VAR_WINDOW = 21;
    public static final int TURN_OF_MONTH_FIRST_DAYS = 3;

    private IndexTimingRules() {
    }

    /**
     * Z1 Moreira &amp; Muir(2017) 분산 역수 비중: min(1, σ²(직전 252일) / σ²(직전 21일)). asOf는 i−1.
     * 원전의 상수 c(전 구간 분산)는 사후 정보라 직전 1년 분산으로 바꾸고 레버리지는 쓰지 않는다.
     */
    public static double volatilityManagedWeight(double[] closes, int asOf) {
        double longVar = variance(closes, asOf, LONG_VAR_WINDOW);
        double shortVar = variance(closes, asOf, SHORT_VAR_WINDOW);
        if (shortVar <= 0) {
            return 1.0;
        }
        return Math.min(1.0, longVar / shortVar);
    }

    /** Z2 Bouman &amp; Jacobsen(2002): 11~4월이면 보유. */
    public static boolean halloweenHold(LocalDate day) {
        int m = day.getMonthValue();
        return m >= 11 || m <= 4;
    }

    /**
     * Z4 Lakonishok &amp; Smidt(1988): 날 i가 그 달의 마지막 거래일이거나 첫 3거래일이면 보유.
     * 마지막 거래일 여부는 다음 거래일이 다른 달인지로 본다(거래소 휴장 달력은 미리 알려진 정보).
     * 달력의 마지막 날은 다음 날을 모르므로 마지막 거래일로 보지 않는다.
     */
    public static boolean turnOfMonthHold(List<LocalDate> calendar, int i) {
        LocalDate d = calendar.get(i);
        boolean lastOfMonth = i + 1 < calendar.size() && calendar.get(i + 1).getMonthValue() != d.getMonthValue();
        int rankInMonth = 1;
        for (int k = i - 1; k >= 0 && calendar.get(k).getMonthValue() == d.getMonthValue()
                && calendar.get(k).getYear() == d.getYear(); k--) {
            rankInMonth++;
        }
        return lastOfMonth || rankInMonth <= TURN_OF_MONTH_FIRST_DAYS;
    }

    /** closes[asOf−window .. asOf]의 일수익률 window개의 모분산. */
    static double variance(double[] closes, int asOf, int window) {
        double sum = 0;
        double sumSq = 0;
        for (int j = asOf - window + 1; j <= asOf; j++) {
            double r = closes[j] / closes[j - 1] - 1;
            sum += r;
            sumSq += r * r;
        }
        double mean = sum / window;
        return sumSq / window - mean * mean;
    }
}

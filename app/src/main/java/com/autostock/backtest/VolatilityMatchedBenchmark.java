package com.autostock.backtest;

/**
 * 게이트 ① v3(ADR-15, autoStock D-30)의 SPA 벤치마크 — 후보와 변동성을 맞춘 "지수 w + 현금 (1−w)".
 *
 * <p>총수익 비교는 노출이 작은 전략(자산배분·현금 대기)에 구조적으로 불리하다. 그래서 후보 k마다
 * {@code w_k = min(1, σ_k / σ_bench)}(같은 구간 일수익률 표준편차)로 지수 비중을 낮춘 벤치마크를 만든다.
 * 현금 수익률은 0%로 둔다. 후보 쪽(D·Y 계열) 현금도 0%로 계산하므로 양쪽 가정이 같다.
 *
 * <p><b>w는 1을 넘지 않는다.</b> 벤치마크는 실제로 따라 할 수 있는 조합이어야 한다(레버리지 없음). 후보가
 * 지수보다 변동성이 크면 w=1이고 v2의 KODEX200 B&amp;H 검정과 같아진다. 규칙 원문:
 * docs/research/adr15_preregistration_20261114.md 2장.
 */
public final class VolatilityMatchedBenchmark {

    private VolatilityMatchedBenchmark() {
    }

    /** 지수 비중 w = min(1, σ후보/σ지수). 지수 변동성이 0이면 1(맞출 기준이 없음). */
    public static double weight(double[] candidateReturns, double[] benchmarkReturns) {
        requireSameLength(candidateReturns, benchmarkReturns);
        double sigmaBench = populationStd(benchmarkReturns);
        if (sigmaBench == 0.0) {
            return 1.0;
        }
        return Math.min(1.0, populationStd(candidateReturns) / sigmaBench);
    }

    /** 후보 하나에 맞춘 벤치마크 일수익률 {@code w × r_bench}(나머지는 현금 0%). */
    public static double[] series(double[] candidateReturns, double[] benchmarkReturns) {
        double w = weight(candidateReturns, benchmarkReturns);
        double[] out = new double[benchmarkReturns.length];
        for (int t = 0; t < out.length; t++) {
            out[t] = w * benchmarkReturns[t];
        }
        return out;
    }

    private static void requireSameLength(double[] a, double[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            throw new IllegalArgumentException("후보와 벤치마크는 같은 길이(>0)로 날짜를 맞춰야 함");
        }
    }

    private static double populationStd(double[] a) {
        double mean = 0.0;
        for (double v : a) {
            mean += v;
        }
        mean /= a.length;
        double sumSq = 0.0;
        for (double v : a) {
            double d = v - mean;
            sumSq += d * d;
        }
        return Math.sqrt(sumSq / a.length);
    }
}

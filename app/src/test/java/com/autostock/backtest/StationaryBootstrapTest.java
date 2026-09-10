package com.autostock.backtest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StationaryBootstrap} 단위테스트 — F1(트랙 F, ADR-13). 실제 시장데이터에 의존하지
 * 않는 순수 합성 데이터로만 검증한다(재현성·형태·통계적 성질의 "느슨한" sanity 체크).
 */
class StationaryBootstrapTest {

    @Test
    void 시드가_같으면_리샘플_경로가_완전히_재현된다() {
        double[] returns = syntheticReturns(500, 1L);

        StationaryBootstrap b1 = new StationaryBootstrap(returns, 21, 50, 42L);
        StationaryBootstrap b2 = new StationaryBootstrap(returns, 21, 50, 42L);

        double[][] paths1 = b1.resamplePaths().paths();
        double[][] paths2 = b2.resamplePaths().paths();

        assertEquals(paths1.length, paths2.length);
        for (int b = 0; b < paths1.length; b++) {
            assertEquals(paths1[b].length, paths2[b].length);
            for (int t = 0; t < paths1[b].length; t++) {
                assertEquals(paths1[b][t], paths2[b][t], 0.0,
                        "시드 고정인데 리샘플 값이 다름(b=" + b + ", t=" + t + ")");
            }
        }
    }

    @Test
    void 시드가_다르면_리샘플_경로가_달라진다() {
        double[] returns = syntheticReturns(500, 1L);

        StationaryBootstrap b1 = new StationaryBootstrap(returns, 21, 50, 42L);
        StationaryBootstrap b2 = new StationaryBootstrap(returns, 21, 50, 43L);

        double[][] paths1 = b1.resamplePaths().paths();
        double[][] paths2 = b2.resamplePaths().paths();

        boolean anyDifferent = false;
        outer:
        for (int b = 0; b < paths1.length; b++) {
            for (int t = 0; t < paths1[b].length; t++) {
                if (paths1[b][t] != paths2[b][t]) {
                    anyDifferent = true;
                    break outer;
                }
            }
        }
        assertTrue(anyDifferent, "다른 시드인데 모든 리샘플 값이 우연히 완전히 같음 — 시드가 반영 안 되고 있을 가능성");
    }

    @Test
    void 리샘플_경로_길이는_항상_원본_길이와_같다() {
        double[] returns = syntheticReturns(365, 2L);
        int resamples = 100;
        StationaryBootstrap bootstrap = new StationaryBootstrap(returns, 21, resamples, 7L);

        double[][] paths = bootstrap.resamplePaths().paths();

        assertEquals(resamples, paths.length);
        for (double[] path : paths) {
            assertEquals(returns.length, path.length, "리샘플 경로 길이는 원본 길이와 같아야 함");
        }
    }

    @Test
    void 표본_블록길이_평균은_설정한_L에_근사한다() {
        int meanBlockLength = 21;
        double[] returns = syntheticReturns(2000, 3L);
        StationaryBootstrap bootstrap = new StationaryBootstrap(returns, meanBlockLength, 500, 99L);

        List<List<Integer>> blockLengthsPerPath = bootstrap.resamplePaths().blockLengths();

        long totalBlocks = 0;
        long totalLength = 0;
        for (List<Integer> lens : blockLengthsPerPath) {
            for (int len : lens) {
                totalBlocks++;
                totalLength += len;
            }
        }
        double observedMean = totalLength / (double) totalBlocks;

        // 기하분포 평균이므로 표본 평균은 변동이 크다 — 상식적 허용오차(±30%)만 확인(느슨한 sanity)
        assertTrue(observedMean > meanBlockLength * 0.7 && observedMean < meanBlockLength * 1.3,
                "표본 블록길이 평균(" + observedMean + ")이 L=" + meanBlockLength + "에서 너무 벗어남");
    }

    @Test
    void iid_정규_합성수익률의_MDD_중앙값은_상식적_범위_안에_있다() {
        // 연 8%/변동성 20% 근사(일별 mu, sigma) — 흔한 주식형 자산 가정. 5년치(1260거래일).
        double dailyMu = 0.08 / 252.0;
        double dailySigma = 0.20 / Math.sqrt(252.0);
        double[] returns = normalReturns(1260, dailyMu, dailySigma, 123L);

        StationaryBootstrap bootstrap = new StationaryBootstrap(returns);
        StationaryBootstrap.MddSummary summary = bootstrap.mddDistribution();

        assertTrue(!Double.isNaN(summary.median()) && !Double.isNaN(summary.p95()));
        assertTrue(summary.p95() >= summary.median(), "p95는 median 이상이어야 함");
        // 연 8%/변동성 20%짜리 5년 경로의 MDD가 5%보다 작거나 90%보다 크면 뭔가 잘못됐다는
        // 신호다 — 정밀한 이론값 검증이 아니라 "말이 되는 범위"인지만 보는 느슨한 sanity.
        assertTrue(summary.median() > 0.05 && summary.median() < 0.90,
                "i.i.d. 정규 합성수익률 MDD 중앙값(" + summary.median() + ")이 상식적 범위를 벗어남");
        assertTrue(summary.probabilityExceedsBudget() >= 0.0 && summary.probabilityExceedsBudget() <= 1.0);
    }

    @Test
    void 잘못된_생성자_입력은_예외를_던진다() {
        try {
            new StationaryBootstrap(new double[0]);
            assertTrue(false, "빈 배열에 예외가 없음");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new StationaryBootstrap(new double[]{0.01, 0.02}, 0, 10, 1L);
            assertTrue(false, "meanBlockLength<=0에 예외가 없음");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new StationaryBootstrap(new double[]{0.01, 0.02}, 5, 0, 1L);
            assertTrue(false, "resamples<=0에 예외가 없음");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    /** 결정적(시드 고정) 합성 수익률 — 평균 0 근방, 약간의 자기상관 없이 단순 균등분포 기반. */
    private double[] syntheticReturns(int n, long seed) {
        Random rnd = new Random(seed);
        double[] returns = new double[n];
        for (int i = 0; i < n; i++) {
            returns[i] = (rnd.nextDouble() - 0.5) * 0.02; // [-1%, +1%] 근방
        }
        return returns;
    }

    /** 시드 고정 정규분포(Box-Muller via Random#nextGaussian) 합성 수익률. */
    private double[] normalReturns(int n, double mu, double sigma, long seed) {
        Random rnd = new Random(seed);
        double[] returns = new double[n];
        for (int i = 0; i < n; i++) {
            returns[i] = mu + sigma * rnd.nextGaussian();
        }
        return returns;
    }
}

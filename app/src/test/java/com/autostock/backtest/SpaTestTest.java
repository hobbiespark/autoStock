package com.autostock.backtest;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SpaTest} 단위테스트 — F2(트랙 F, ADR-13). 순수 합성 데이터로만 검증한다.
 *
 * <h2>합성 데이터 설계 — 왜 후보를 벤치마크에 "연동"시키는가</h2>
 * 후보 수익률을 벤치마크와 독립으로 만들면(각자 다른 RNG로 생성) {@code d_k,t = r_k,t - r_bench,t}
 * 의 분산이 두 시계열의 분산을 합친 만큼 커져서(예: 벤치마크 일변동성 1%면 그것만으로 d의
 * 노이즈가 지배적이 됨), 현실적인 "5bp/일 우위" 정도로는 유의성이 전혀 나오지 않는다(실제
 * 시장 벤치마크 대비 개별 전략도 대부분 이렇게 연동돼 있다 — 트래킹에러가 총변동성보다 훨씬
 * 작다). 그래서 이 테스트의 후보들은 항상 {@code candidate = bench + (엣지 평균) + (작은
 * 고유노이즈)} 형태로 만든다 — d_k,t는 그러면 딱 "엣지+고유노이즈"만 남아 스케일이 현실적이다.
 */
class SpaTestTest {

    private static final int OBSERVATIONS = 500;
    // 실험 테스트보다 짧게 잡아 단위테스트 실행시간을 보장(느슨한 sanity 목적이므로 충분).
    private static final int RESAMPLES = 500;

    @Test
    void 명백한_우위_후보가_있으면_pvalue가_작다() {
        double[] bench = normalReturns(OBSERVATIONS, 0.0, 0.01, 1L);
        Map<String, double[]> candidates = new LinkedHashMap<>();
        // 명백한 엣지: 벤치마크 대비 평균 +5bp/일, 고유노이즈는 작게(0.2%)
        candidates.put("우위후보", trackBenchmark(bench, 0.0005, 0.002, 2L));
        candidates.put("노이즈1", trackBenchmark(bench, 0.0, 0.002, 3L));
        candidates.put("노이즈2", trackBenchmark(bench, 0.0, 0.002, 4L));

        SpaTest spa = new SpaTest(bench, candidates, 21, RESAMPLES, 42L);
        SpaTest.SpaResult result = spa.run();

        assertTrue(result.pValueSpaConsistent() < 0.05,
                "명백한 우위 후보가 있는데 SPA_c p-value(" + result.pValueSpaConsistent() + ")가 0.05 이상임");
    }

    @Test
    void 순수_노이즈_후보들만_있으면_pvalue가_크다() {
        double[] bench = normalReturns(OBSERVATIONS, 0.0, 0.01, 10L);
        Map<String, double[]> candidates = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            // 엣지 없음(평균 0) — 벤치마크에 연동된 고유노이즈만
            candidates.put("노이즈" + i, trackBenchmark(bench, 0.0, 0.003, 20L + i));
        }

        SpaTest spa = new SpaTest(bench, candidates, 21, RESAMPLES, 42L);
        SpaTest.SpaResult result = spa.run();

        assertTrue(result.pValueSpaConsistent() > 0.10,
                "순수 노이즈 후보만 있는데 SPA_c p-value(" + result.pValueSpaConsistent() + ")가 0.10 이하임");
    }

    @Test
    void 시드가_같으면_pvalue가_재현된다() {
        double[] bench = normalReturns(OBSERVATIONS, 0.0, 0.01, 30L);
        Map<String, double[]> candidates = new LinkedHashMap<>();
        candidates.put("A", trackBenchmark(bench, 0.0002, 0.003, 31L));
        candidates.put("B", trackBenchmark(bench, -0.0001, 0.003, 32L));

        SpaTest.SpaResult r1 = new SpaTest(bench, candidates, 21, RESAMPLES, 777L).run();
        SpaTest.SpaResult r2 = new SpaTest(bench, candidates, 21, RESAMPLES, 777L).run();

        assertEquals(r1.pValueSpaConsistent(), r2.pValueSpaConsistent(), 0.0);
        assertEquals(r1.testStatistic(), r2.testStatistic(), 0.0);
    }

    @Test
    void 열등후보를_대량추가해도_유효후보의_pvalue가_크게_나빠지지_않는다() {
        double[] bench = normalReturns(OBSERVATIONS, 0.0, 0.01, 40L);
        double[] goodCandidate = trackBenchmark(bench, 0.0005, 0.002, 41L);

        Map<String, double[]> soloPool = new LinkedHashMap<>();
        soloPool.put("우위후보", goodCandidate);
        double pSolo = new SpaTest(bench, soloPool, 21, RESAMPLES, 42L).run().pValueSpaConsistent();

        Map<String, double[]> crowdedPool = new LinkedHashMap<>();
        crowdedPool.put("우위후보", goodCandidate);
        for (int i = 0; i < 20; i++) {
            // 명백히 열등한 후보(음의 엣지) 대량 추가
            crowdedPool.put("열등" + i, trackBenchmark(bench, -0.001, 0.003, 100L + i));
        }
        double pCrowded = new SpaTest(bench, crowdedPool, 21, RESAMPLES, 42L).run().pValueSpaConsistent();

        // SPA의 핵심 성질 — 열등 후보가 아무리 많이 섞여도(스튜던트화 덕분에) 유효 후보의
        // 검정력이 크게 훼손되지 않아야 한다. 정밀 일치는 요구하지 않고(부트스트랩 몬테카를로
        // 오차 있음) "크게 나빠지지 않는다"는 느슨한 assert만 한다.
        assertTrue(pCrowded <= pSolo + 0.10,
                "열등후보 20개 추가 후 p-value(" + pCrowded + ")가 단독 p-value(" + pSolo + ")보다 크게 나빠짐");
        assertTrue(pCrowded < 0.10, "열등후보가 섞여도 유효후보 존재 시 p-value는 여전히 작아야 함(" + pCrowded + ")");
    }

    @Test
    void 후보_길이가_벤치마크와_다르면_예외() {
        double[] bench = normalReturns(100, 0.0, 0.01, 1L);
        Map<String, double[]> candidates = new LinkedHashMap<>();
        candidates.put("짧음", normalReturns(50, 0.0, 0.01, 2L));
        try {
            new SpaTest(bench, candidates);
            assertTrue(false, "길이 불일치인데 예외가 없음");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    /** candidate_t = bench_t + edgeMu + 고유노이즈(idioSigma, 독립 시드) — 클래스 Javadoc 참고. */
    private double[] trackBenchmark(double[] bench, double edgeMu, double idioSigma, long seed) {
        Random rnd = new Random(seed);
        double[] out = new double[bench.length];
        for (int i = 0; i < bench.length; i++) {
            out[i] = bench[i] + edgeMu + idioSigma * rnd.nextGaussian();
        }
        return out;
    }

    private double[] normalReturns(int n, double mu, double sigma, long seed) {
        Random rnd = new Random(seed);
        double[] returns = new double[n];
        for (int i = 0; i < n; i++) {
            returns[i] = mu + sigma * rnd.nextGaussian();
        }
        return returns;
    }
}

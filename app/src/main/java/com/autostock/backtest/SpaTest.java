package com.autostock.backtest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hansen (2005, Journal of Business &amp; Economic Statistics) "A Test for Superior Predictive
 * Ability" (SPA) 검정 — White(2000) Reality Check를 스튜던트화 + 표본 의존(sample-dependent)
 * 널분포로 개선한 데이터 스누핑 공식 검정.
 *
 * <h2>무엇을 검정하는가</h2>
 * 벤치마크(예: KODEX200 buy&amp;hold) 대비 후보 전략 K개의 초과수익 {@code d_k,t = r_k,t - r_bench,t}
 * 를 본다. 귀무가설 H0: "모든 후보의 (진짜) 평균 초과수익은 0 이하다" — 즉 "그 어떤 후보도
 * 벤치마크를 이긴다고 말할 수 없다". SPA_c p-value가 작을수록(예: &lt;0.05) "적어도 하나의
 * 후보는 우연이 아니게 벤치마크를 이긴다"는 증거가 강하다는 뜻이다.
 *
 * <h2>왜 DSR 단독보다 나은가 — PLAN ADR-12 배경</h2>
 * White Reality Check/Hansen SPA는 "풀에 열등한 후보가 많이 섞여도 검정력이 크게 훼손되지
 * 않는다"(스튜던트화 덕분에 후보별 변동성으로 나눠 정규화하므로, 명백히 나쁜 후보가 최댓값
 * 통계량에 기여할 여지가 거의 없다). DSR은 "N=시도한 계열 수"를 그대로 다중검정 벌점에
 * 반영해 N이 커질수록 보수적으로 벌점을 주므로, "어떤 계열까지 N에 넣을지" 자체가 논쟁이
 * 된다 — SPA는 이 논쟁을 구조적으로 줄여준다(F2 단위테스트 ④가 이 성질을 확인한다).
 *
 * <h2>구현 — SPA_c(consistent)만 산출, lower/upper는 생략</h2>
 * Hansen(2005)은 재중심화(recentering) 방식에 따라 세 변형을 정의한다:
 * <ul>
 *   <li>SPA_l(lower, 가장 보수적): 모든 후보를 "진짜 평균 0"으로 재중심화 — 가장 큰 p-value.</li>
 *   <li>SPA_u(upper, 가장 관대): 모든 후보를 "진짜 평균 = max(관측평균,0)"으로 재중심화 — 가장
 *       작은 p-value.</li>
 *   <li>SPA_c(consistent, 이 클래스가 계산하는 것): 후보별 t-통계량이
 *       {@code -sqrt(2 log log T)}보다 낮으면(명백히 열등) 그 후보의 재중심화 평균을 0으로
 *       처리하고, 그렇지 않으면 관측 평균을 그대로 쓴다 — Hansen이 실무 기본값으로 권장하는
 *       중간 지점이다. 이 클래스는 SPA_c만 산출한다(PLAN 스펙 명시 — lower/upper는 범위 밖).</li>
 * </ul>
 *
 * <h2>분산(ω̂_k²) 추정 — 구현 단순화</h2>
 * Hansen(2005) 원 논문은 ω̂_k²을 HAC(Newey-West류) 장기분산 추정량으로 계산하지만, 이 구현은
 * {@link StationaryBootstrap}으로 이미 만든 부트스트랩 표본을 그대로 재사용해 "부트스트랩
 * 평균의 표본분산"으로 ω̂_k²을 근사한다({@code ω̂_k² ≈ Var_b[sqrt(T)·d̄*_k,b]}) — 별도의 HAC
 * 대역폭 선택 로직을 새로 구현하지 않기 위한 의도적 단순화다(정확도보다 "튜닝 표면을 늘리지
 * 않는 것"을 우선했다 — PLAN 과최적화 방지 원칙과 같은 맥락).
 *
 * <h2>부트스트랩 인덱스 공유 — F1 재사용</h2>
 * 벤치마크와 K개 후보의 {@code d_k,t} 시계열 모두에 "같은" 시간 인덱스 리샘플을 적용해야
 * 교차상관 구조가 보존된다. 그래서 {@link StationaryBootstrap#generateIndexPaths}(F1의 정적
 * 유틸)를 한 번만 호출해 인덱스 B개를 만들고, 그 인덱스를 모든 후보에 동일하게 적용한다.
 */
public final class SpaTest {

    public static final int DEFAULT_MEAN_BLOCK_LENGTH = StationaryBootstrap.DEFAULT_MEAN_BLOCK_LENGTH;
    public static final int DEFAULT_RESAMPLES = StationaryBootstrap.DEFAULT_RESAMPLES;
    public static final long DEFAULT_SEED = StationaryBootstrap.DEFAULT_SEED;

    private final double[] benchmarkReturns;
    private final List<String> candidateLabels;
    private final List<double[]> candidateReturns;
    private final int meanBlockLength;
    private final int resamples;
    private final long seed;

    public SpaTest(double[] benchmarkReturns, Map<String, double[]> candidates) {
        this(benchmarkReturns, candidates, DEFAULT_MEAN_BLOCK_LENGTH, DEFAULT_RESAMPLES, DEFAULT_SEED);
    }

    public SpaTest(double[] benchmarkReturns, Map<String, double[]> candidates,
                    int meanBlockLength, int resamples, long seed) {
        if (benchmarkReturns == null || benchmarkReturns.length == 0) {
            throw new IllegalArgumentException("benchmarkReturns가 비어 있음");
        }
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("candidates가 비어 있음");
        }
        int t = benchmarkReturns.length;
        this.candidateLabels = new ArrayList<>();
        this.candidateReturns = new ArrayList<>();
        for (Map.Entry<String, double[]> e : candidates.entrySet()) {
            if (e.getValue() == null || e.getValue().length != t) {
                throw new IllegalArgumentException("후보 '" + e.getKey() + "'의 길이(" +
                        (e.getValue() == null ? "null" : e.getValue().length) +
                        ")가 벤치마크 길이(" + t + ")와 다름 — 공통 날짜 정렬을 먼저 해야 함");
            }
            candidateLabels.add(e.getKey());
            candidateReturns.add(e.getValue().clone());
        }
        this.benchmarkReturns = benchmarkReturns.clone();
        this.meanBlockLength = meanBlockLength;
        this.resamples = resamples;
        this.seed = seed;
    }

    /** 후보 하나의 통계 요약 — 개별 t-stat 출력용(PLAN 스펙). */
    public record CandidateStat(String label, double meanExcessReturn, double tStat) {
    }

    /**
     * @param pValueSpaConsistent SPA_c p-value — 작을수록(예: &lt;0.05) "벤치마크를 이기는
     *                            후보가 있다"는 증거가 강함
     * @param testStatistic       관측 검정통계량 T_n = max(0, max_k sqrt(T)·d̄_k/ω̂_k)
     * @param candidateStats      후보별 평균초과수익·t-stat(라벨 순서 = 생성자에 넘긴 맵 순회 순서)
     */
    public record SpaResult(double pValueSpaConsistent, double testStatistic, List<CandidateStat> candidateStats) {
    }

    public SpaResult run() {
        int observations = benchmarkReturns.length;
        int candidateCount = candidateReturns.size();

        // d_k,t = r_k,t - r_bench,t
        double[][] d = new double[candidateCount][observations];
        for (int k = 0; k < candidateCount; k++) {
            double[] c = candidateReturns.get(k);
            for (int t = 0; t < observations; t++) {
                d[k][t] = c[t] - benchmarkReturns[t];
            }
        }

        double[] dBar = new double[candidateCount];
        for (int k = 0; k < candidateCount; k++) {
            dBar[k] = mean(d[k]);
        }

        // ── 벤치마크·후보 전부에 공유하는 부트스트랩 시간 인덱스(F1 재사용) ──
        int[][] bootstrapIndices = StationaryBootstrap.generateIndexPaths(observations, meanBlockLength, resamples, seed);

        // sqrt(T)·d̄*_k,b — 후보별/리샘플별 부트스트랩 평균(스케일 보정됨)
        double[][] bootMeansScaled = new double[candidateCount][resamples];
        double sqrtT = Math.sqrt(observations);
        for (int k = 0; k < candidateCount; k++) {
            double[] dk = d[k];
            for (int b = 0; b < resamples; b++) {
                int[] path = bootstrapIndices[b];
                double sum = 0.0;
                for (int idx : path) {
                    sum += dk[idx];
                }
                bootMeansScaled[k][b] = sqrtT * (sum / observations);
            }
        }

        // ω̂_k² ≈ 부트스트랩 평균(스케일 보정됨)의 표본분산 — 클래스 Javadoc "분산 추정" 참고
        double[] omega = new double[candidateCount];
        for (int k = 0; k < candidateCount; k++) {
            double var = populationVariance(bootMeansScaled[k]);
            omega[k] = Math.sqrt(Math.max(var, 1e-18)); // 0분산 방어(상수 후보 등 엣지 케이스)
        }

        // Hansen SPA_c 재중심화 임계 — sqrt(2·log(log(T))). T가 작아 log(log(T))<=0이면 0으로 방어.
        double logLogT = Math.log(Math.log(Math.max(observations, 3)));
        double loglogTerm = Math.max(0.0, logLogT);

        double[] tStat = new double[candidateCount];
        double[] muConsistent = new double[candidateCount];
        for (int k = 0; k < candidateCount; k++) {
            tStat[k] = sqrtT * dBar[k] / omega[k];
            double threshold = omega[k] * Math.sqrt(2 * loglogTerm / observations);
            // 관측평균이 "명백히 열등"(재중심화 임계보다 더 낮음) 하면 0으로 재중심화(SPA_c 규칙)
            muConsistent[k] = dBar[k] >= -threshold ? dBar[k] : 0.0;
        }

        double testStatistic = 0.0;
        for (double t : tStat) {
            testStatistic = Math.max(testStatistic, t);
        }

        int exceedCount = 0;
        for (int b = 0; b < resamples; b++) {
            double bootStatistic = 0.0;
            for (int k = 0; k < candidateCount; k++) {
                double z = (bootMeansScaled[k][b] - sqrtT * muConsistent[k]) / omega[k];
                bootStatistic = Math.max(bootStatistic, z);
            }
            if (bootStatistic >= testStatistic) {
                exceedCount++;
            }
        }
        double pValue = exceedCount / (double) resamples;

        List<CandidateStat> stats = new ArrayList<>();
        for (int k = 0; k < candidateCount; k++) {
            stats.add(new CandidateStat(candidateLabels.get(k), dBar[k], tStat[k]));
        }

        return new SpaResult(pValue, testStatistic, stats);
    }

    /** 후보 라벨 → 개별 t-stat/평균초과수익만 필요할 때 쓰는 편의 뷰. */
    public Map<String, CandidateStat> candidateStatsByLabel() {
        Map<String, CandidateStat> map = new LinkedHashMap<>();
        for (CandidateStat s : run().candidateStats()) {
            map.put(s.label(), s);
        }
        return map;
    }

    private double mean(double[] a) {
        double sum = 0.0;
        for (double v : a) {
            sum += v;
        }
        return a.length == 0 ? 0.0 : sum / a.length;
    }

    private double populationVariance(double[] a) {
        double m = mean(a);
        double sumSq = 0.0;
        for (double v : a) {
            double diff = v - m;
            sumSq += diff * diff;
        }
        return a.length == 0 ? 0.0 : sumSq / a.length;
    }
}

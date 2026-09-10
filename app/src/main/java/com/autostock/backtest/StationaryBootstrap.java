package com.autostock.backtest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Stationary Bootstrap (Politis &amp; Romano, 1994, JASA — "The Stationary Bootstrap") 엔진.
 *
 * <p>쉬운 설명: 실제로 관측된 수익률 시계열은 딱 "한 경로"뿐이다 — 그 한 경로에서 나온
 * MDD/Sharpe 점추정치는 운(무작위 순서)의 영향을 크게 받을 수 있다. Stationary bootstrap은
 * 원 수익률을 "블록" 단위로 확률적으로 이어붙여 원본과 같은 길이의 가짜 경로를 B개 만들고,
 * 그 B개 경로에서 나온 MDD/Sharpe의 분포를 보면 "이 정도 낙폭/샤프가 얼마나 흔한가"를
 * 가늠할 수 있다.
 *
 * <h2>왜 블록 단위인가 — 단순 i.i.d. 리샘플(bootstrap)과의 차이</h2>
 * 일별 수익률은 변동성 군집(volatility clustering) 등 자기상관이 있어, 하루하루를 완전히
 * 무작위로 섞어버리면(i.i.d. 리샘플) 그 자기상관 구조가 사라져 MDD를 과소평가하게 된다.
 * Politis&amp;Romano의 stationary bootstrap은 블록 길이 자체를 기하분포(geometric)로 무작위화해
 * "블록으로 이어붙이되, 블록 경계와 길이도 확률적"으로 만든다 — 그 결과 리샘플된 과정이
 * (근사적으로) 원 과정과 같은 정상성(stationarity)을 유지한다.
 *
 * <h2>알고리즘 — 정석 구현</h2>
 * 각 리샘플 경로는 길이 n(원 시계열 길이)만큼 다음을 반복해서 만든다:
 * <ol>
 *   <li>현재 포인터 i에서 관측치 {@code returns[i]}를 경로에 채운다.</li>
 *   <li>확률 p(=1/meanBlockLength)로 "블록 종료" — 다음 포인터를 [0,n) 구간에서 균등 무작위로
 *       다시 뽑는다(새 블록 시작, 순환 wrap-around 불필요 — 매번 새로 뽑으므로).</li>
 *   <li>그렇지 않으면(확률 1-p) "블록 계속" — 다음 포인터는 {@code (i+1) % n}
 *       (원 시계열 끝에서 처음으로 순환 wrap-around).</li>
 * </ol>
 * 이 규칙을 따르면 한 블록의 길이는 기하분포 Geometric(p)를 따르고 기댓값은 1/p =
 * meanBlockLength다.
 *
 * <h2>기본값 — 상수로 고정한 이유(새 튜닝 표면 방지)</h2>
 * <ul>
 *   <li>{@link #DEFAULT_MEAN_BLOCK_LENGTH} = 21: 국내 거래일 기준 월 1개 — 일별 수익률의
 *       자기상관(특히 변동성 군집)이 대체로 월 단위 사건(실적 발표, 통화정책 등)에서 비롯되므로
 *       이 스케일을 보존하는 것이 합리적이다(Politis&amp;Romano 원 논문도 "데이터 스케일에 맞는
 *       블록 길이"를 강조할 뿐, 유일한 최적값을 주지 않는다 — 여기서는 사후 데이터 확인 없이
 *       월 단위라는 상식적 근거만으로 사전에 고정한다).</li>
 *   <li>{@link #DEFAULT_RESAMPLES} = 2,000: 퍼센타일(p95 등) 추정의 몬테카를로 오차를 줄이기
 *       충분한 표본 수이면서(1/2000=0.05%p 해상도) 10계열 실험에서도 감당 가능한 계산량.</li>
 *   <li>{@link #DEFAULT_SEED} = 42: 재현성 고정. 이 값 자체에 특별한 의미는 없다.</li>
 * </ul>
 * 이 상수들은 "게이트 판정 기준"이 아니라 "측정 도구의 설정값"이므로 ADR-13에 따라 결과를
 * 본 뒤 바꾸지 않는다(그렇게 하면 그 자체가 새로운 튜닝 표면이 된다).
 */
public final class StationaryBootstrap {

    /** 기본 평균 블록길이(거래일) — 월 단위 자기상관 보존 목적. 클래스 Javadoc 참고. */
    public static final int DEFAULT_MEAN_BLOCK_LENGTH = 21;
    /** 기본 리샘플 횟수. */
    public static final int DEFAULT_RESAMPLES = 2000;
    /** 기본 시드 — 재현성 고정용, 값 자체에 의미 없음. */
    public static final long DEFAULT_SEED = 42L;

    /** MDD 분포에서 "예산 초과 확률"을 낼 때 쓰는 기준선 — 계좌 전체 MDD 예산(PLAN 게이트①). */
    private static final double DEFAULT_MDD_BUDGET = 0.15;

    private final double[] returns;
    private final int meanBlockLength;
    private final int resamples;
    private final long seed;

    public StationaryBootstrap(double[] returns) {
        this(returns, DEFAULT_MEAN_BLOCK_LENGTH, DEFAULT_RESAMPLES, DEFAULT_SEED);
    }

    public StationaryBootstrap(double[] returns, int meanBlockLength, int resamples, long seed) {
        if (returns == null || returns.length == 0) {
            throw new IllegalArgumentException("returns가 비어 있음");
        }
        if (meanBlockLength <= 0) {
            throw new IllegalArgumentException("meanBlockLength는 양수여야 함: " + meanBlockLength);
        }
        if (resamples <= 0) {
            throw new IllegalArgumentException("resamples는 양수여야 함: " + resamples);
        }
        this.returns = returns.clone();
        this.meanBlockLength = meanBlockLength;
        this.resamples = resamples;
        this.seed = seed;
    }

    public int originalLength() {
        return returns.length;
    }

    public int meanBlockLength() {
        return meanBlockLength;
    }

    public int resamples() {
        return resamples;
    }

    public long seed() {
        return seed;
    }

    /**
     * 인덱스 생성 결과 — 리샘플 경로 B개의 원본 인덱스(0..n-1) 시퀀스와, 경로별로 실제 뽑힌
     * 블록 길이 목록(진단/테스트용).
     */
    private record IndexGenerationResult(int[][] indices, List<List<Integer>> blockLengths) {
    }

    /**
     * 리샘플 경로 B개(각 길이 n) + 경로별 값. {@link SpaTest}가 여러 시계열(벤치마크·후보들)에
     * 동일한 시간 인덱스 리샘플을 공유해서 적용해야 하므로, 인덱스 생성 로직은
     * {@link #generateIndexPaths}로 별도 공개돼 있다 — 이 메서드는 그 인덱스를 이 인스턴스의
     * {@code returns}에 적용한 결과다.
     */
    public record ResampleOutput(double[][] paths, List<List<Integer>> blockLengths) {
    }

    public ResampleOutput resamplePaths() {
        int n = returns.length;
        IndexGenerationResult gen = generateIndicesWithBlockLengths(n, meanBlockLength, resamples, seed);
        double[][] paths = new double[resamples][n];
        for (int b = 0; b < resamples; b++) {
            int[] path = gen.indices()[b];
            for (int t = 0; t < n; t++) {
                paths[b][t] = returns[path[t]];
            }
        }
        return new ResampleOutput(paths, gen.blockLengths());
    }

    /**
     * 리샘플 경로의 "원본 인덱스" 시퀀스만 생성한다({@code returns} 값과 무관 — 길이 n짜리
     * 아무 시계열에나 적용 가능). {@link SpaTest}가 벤치마크와 K개 후보 각각의
     * {@code d_k,t = r_k,t - r_bench,t} 시계열에 "같은" 시간 인덱스 리샘플을 적용해야
     * (교차상관 구조를 보존한 채) 부트스트랩 분포를 만들 수 있으므로 정적 유틸로 분리했다.
     *
     * @param n               원 시계열 길이
     * @param meanBlockLength 평균 블록길이 L
     * @param resamples       리샘플 횟수 B
     * @param seed            RNG 시드
     * @return {@code indices[b][t]} = b번째 리샘플 경로의 t번째 위치가 가리키는 원본 인덱스
     */
    public static int[][] generateIndexPaths(int n, int meanBlockLength, int resamples, long seed) {
        return generateIndicesWithBlockLengths(n, meanBlockLength, resamples, seed).indices();
    }

    private static IndexGenerationResult generateIndicesWithBlockLengths(
            int n, int meanBlockLength, int resamples, long seed) {
        if (n <= 0) {
            throw new IllegalArgumentException("n은 양수여야 함: " + n);
        }
        if (meanBlockLength <= 0) {
            throw new IllegalArgumentException("meanBlockLength는 양수여야 함: " + meanBlockLength);
        }
        if (resamples <= 0) {
            throw new IllegalArgumentException("resamples는 양수여야 함: " + resamples);
        }

        double p = 1.0 / meanBlockLength;
        int[][] indices = new int[resamples][n];
        List<List<Integer>> allBlockLengths = new ArrayList<>(resamples);
        Random rnd = new Random(seed);

        for (int b = 0; b < resamples; b++) {
            List<Integer> blockLengths = new ArrayList<>();
            int i = rnd.nextInt(n);
            int currentLen = 0;
            for (int t = 0; t < n; t++) {
                indices[b][t] = i;
                currentLen++;
                if (rnd.nextDouble() < p) {
                    // 블록 종료 — 새 블록을 [0,n)에서 무작위 시작점으로 다시 시작(랜덤 시작점).
                    blockLengths.add(currentLen);
                    currentLen = 0;
                    i = rnd.nextInt(n);
                } else {
                    // 블록 계속 — 원 시계열 끝에서 처음으로 순환(wrap-around).
                    i = (i + 1) % n;
                }
            }
            if (currentLen > 0) {
                blockLengths.add(currentLen); // 경로 끝에서 잘린 마지막 블록
            }
            allBlockLengths.add(blockLengths);
        }
        return new IndexGenerationResult(indices, allBlockLengths);
    }

    /** 경로별 MDD 분포 요약. */
    public record MddSummary(double median, double p95, double probabilityExceedsBudget) {
    }

    /** 경로별 (비연율화 아님 — 연율화) Sharpe 분포 요약. */
    public record SharpeSummary(double p5, double median) {
    }

    /** MDD 분포 — 예산 기준선은 게이트①의 계좌 전체 MDD 예산 15%(PLAN). */
    public MddSummary mddDistribution() {
        return mddDistribution(DEFAULT_MDD_BUDGET);
    }

    public MddSummary mddDistribution(double mddBudget) {
        double[][] paths = resamplePaths().paths();
        PerformanceCalculator calc = new PerformanceCalculator();
        double[] mdds = new double[resamples];
        int exceedCount = 0;
        for (int b = 0; b < resamples; b++) {
            double mdd = calc.mdd(toList(paths[b]));
            mdds[b] = mdd;
            if (mdd > mddBudget) {
                exceedCount++;
            }
        }
        Arrays.sort(mdds);
        return new MddSummary(percentile(mdds, 0.50), percentile(mdds, 0.95), exceedCount / (double) resamples);
    }

    /** Sharpe(연율화) 분포 — {@link PerformanceCalculator#sharpe}과 같은 컨벤션. */
    public SharpeSummary sharpeDistribution() {
        double[][] paths = resamplePaths().paths();
        PerformanceCalculator calc = new PerformanceCalculator();
        double[] sharpes = new double[resamples];
        for (int b = 0; b < resamples; b++) {
            sharpes[b] = calc.sharpe(toList(paths[b]));
        }
        Arrays.sort(sharpes);
        return new SharpeSummary(percentile(sharpes, 0.05), percentile(sharpes, 0.50));
    }

    private static List<Double> toList(double[] arr) {
        List<Double> list = new ArrayList<>(arr.length);
        for (double v : arr) {
            list.add(v);
        }
        return list;
    }

    /** 선형보간 퍼센타일 — {@code sortedAsc}는 이미 오름차순 정렬돼 있어야 함. */
    private static double percentile(double[] sortedAsc, double q) {
        int n = sortedAsc.length;
        if (n == 1) {
            return sortedAsc[0];
        }
        double pos = q * (n - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) {
            return sortedAsc[lo];
        }
        double frac = pos - lo;
        return sortedAsc[lo] * (1 - frac) + sortedAsc[hi] * frac;
    }
}

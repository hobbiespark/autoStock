package com.autostock.backtest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 일별 수익률 시계열을 받아 성과지표(Sharpe, MDD, CAGR, DSR 등)를 계산한다.
 *
 * <p>외부 통계 라이브러리(Apache Commons Math 등)를 의도적으로 쓰지 않는다 — 여기 필요한
 * 계산은 표준정규분포 CDF/역함수, 평균/표준편차/왜도/첨도뿐이라 직접 구현해도
 * 코드량이 적고, 의존성을 하나 줄이는 편이 프로젝트 방침(PLAN 과최적화 방지 원칙)에 맞다.
 *
 * <h2>DSR(Deflated Sharpe Ratio)이란?</h2>
 * 전략을 여러 번(파라미터 튜닝, 다른 변형 등) 시도하다 보면 그중 하나는 "운으로" 높은
 * 샤프비율이 나올 수 있다(다중검정 문제). DSR은 "이 정도 샤프비율이 순전히 우연히
 * 나올 확률"을 빼고 "진짜 실력으로 봐도 되는 확률"을 0~1 사이 값으로 알려준다.
 * 출처: Bailey, D. H., &amp; López de Prado, M. (2014). "The Deflated Sharpe Ratio:
 * Correcting for Selection Bias, Backtest Overfitting, and Non-Normality."
 */
public final class PerformanceCalculator {

    /** 연환산 기준 거래일수 (국내 주식시장 관행값). */
    private static final int TRADING_DAYS_PER_YEAR = 252;

    /** 오일러-마스케로니 상수 γ — DSR의 SR0(기대 최대 샤프) 공식에 쓰인다. */
    private static final double EULER_MASCHERONI = 0.5772156649015329;

    /**
     * 일별 수익률 시계열로부터 모든 성과지표를 계산해 {@link BacktestResult}로 묶어 반환한다.
     *
     * @param dailyReturns   일별 수익률(전일 대비 평가자산 변화율) 시계열
     * @param initialCapital 초기 자본
     * @param finalEquity    최종 평가자산
     * @param tradeCount     체결 횟수
     * @param trials         DSR 계산에 쓸 "시도 횟수"(파라미터/변형을 몇 개나 테스트했는지).
     *                       1이면 시도가 하나뿐이라는 뜻이므로 다중검정 보정을 하지 않는다(SR0=0).
     * @param trialsVariance 시도들 간 샤프비율의 분산 V[SR_trials] — 시도 횟수가 1이면 사용되지 않는다.
     */
    public BacktestResult calculate(List<Double> dailyReturns,
                                     BigDecimal initialCapital,
                                     BigDecimal finalEquity,
                                     int tradeCount,
                                     int trials,
                                     double trialsVariance) {
        int n = dailyReturns.size();

        double totalReturn = initialCapital.signum() == 0
                ? 0.0
                : finalEquity.subtract(initialCapital)
                        .divide(initialCapital, 12, RoundingMode.HALF_UP)
                        .doubleValue();

        double cagr = n > 0 ? Math.pow(1 + totalReturn, TRADING_DAYS_PER_YEAR / (double) n) - 1 : 0.0;
        double maxDrawdown = mdd(dailyReturns);

        double mean = mean(dailyReturns);
        double std = populationStd(dailyReturns, mean);
        // 일별(비연율화) 샤프 — DSR 점근식은 이 스케일(일별 관측치 T개)을 전제로 하므로
        // 연율화된 값을 그대로 넣으면 sqrt(T-1) 항과 스케일이 맞지 않는다. 그래서 DSR
        // 계산에는 일별 샤프를 쓰고, 사용자에게 보여줄 대표값만 연율화한다.
        double sharpeDaily = std == 0.0 ? 0.0 : mean / std;
        double sharpeAnnualized = sharpeDaily * Math.sqrt(TRADING_DAYS_PER_YEAR);

        double skew = skewness(dailyReturns, mean, std);
        double kurt = kurtosis(dailyReturns, mean, std);
        double dsr = deflatedSharpeRatio(sharpeDaily, trials, trialsVariance, skew, kurt, n);

        return new BacktestResult(
                List.copyOf(dailyReturns),
                finalEquity,
                totalReturn,
                cagr,
                maxDrawdown,
                sharpeAnnualized,
                tradeCount,
                dsr
        );
    }

    /** 연율화 샤프비율만 필요할 때 쓰는 편의 메서드 (mean/std × √252). */
    public double sharpe(List<Double> dailyReturns) {
        double mean = mean(dailyReturns);
        double std = populationStd(dailyReturns, mean);
        return std == 0.0 ? 0.0 : (mean / std) * Math.sqrt(TRADING_DAYS_PER_YEAR);
    }

    /**
     * 최대낙폭(MDD). 수익률을 1.0에서 시작하는 누적 자산곡선으로 바꾼 뒤,
     * "지금까지의 최고점 대비 지금이 얼마나 빠졌는가"의 최댓값을 구한다.
     */
    public double mdd(List<Double> dailyReturns) {
        double equity = 1.0;
        double peak = 1.0;
        double maxDrawdown = 0.0;
        for (double r : dailyReturns) {
            equity *= (1 + r);
            peak = Math.max(peak, equity);
            double drawdown = (peak - equity) / peak;
            maxDrawdown = Math.max(maxDrawdown, drawdown);
        }
        return maxDrawdown;
    }

    /**
     * DSR(Deflated Sharpe Ratio) 계산 — trials 기반 범용 공식.
     *
     * <pre>
     *   SR0 = sqrt(V[SR_trials]) × ((1-γ)·Φ⁻¹(1-1/N) + γ·Φ⁻¹(1-1/(N·e)))   (N=1이면 SR0=0)
     *   DSR = Φ( (SR_obs - SR0)·sqrt(T-1) / sqrt(1 - skew·SR_obs + ((kurt-1)/4)·SR_obs²) )
     * </pre>
     *
     * <p><b>⚠ 이 메서드를 최종 게이트(예: 게이트① OOS DSR &gt; 0.95) 판정에 직접 쓰지 말 것.</b>
     * {@link WalkForwardRunner}는 이 메서드를 trials = "파라미터 후보 수 × 창(window) 수"로
     * 호출한다({@link #calculate}를 거쳐 {@link BacktestResult#dsrConfidence()}로 노출됨) —
     * 이건 <b>train 단계에서 파라미터를 고르는 다중검정을 보정하기 위한 값</b>이지, 최종
     * OOS 성과를 "여러 전략 계열 중 어떤 걸 채택할지" 판단하는 값이 아니다. 후보 수가
     * 5개, 창이 25개면 N=125가 되는데, N이 이렇게 커지면 SR0(운으로 기대되는 최대 샤프)가
     * 관측된 샤프보다 항상 커져서 DSR이 사실상 항상 0에 가깝게 나온다 — walk-forward OOS
     * 시계열은 "선택이 이미 끝난 단일 결과"이므로 이 N을 그대로 최종 게이트에 쓰면 방법론
     * 오류다. OOS 레벨 최종 게이트에는 반드시 {@link #deflatedSharpeAcrossFamilies}를 써라.
     *
     * @param srObserved     관측된 (비연율화) 샤프비율
     * @param trials         시도 횟수 N
     * @param trialsVariance 시도들 간 샤프비율 분산 V[SR_trials]
     * @param skew           일별 수익률의 왜도
     * @param kurt           일별 수익률의 첨도(비초과, 정규분포=3)
     * @param observations   관측치 수 T(일수)
     */
    public double deflatedSharpeRatio(double srObserved, int trials, double trialsVariance,
                                       double skew, double kurt, int observations) {
        if (observations <= 1) {
            // 관측치가 1개 이하면 통계적으로 아무 것도 판단할 수 없다 — 완전한 불확실성(50%)으로 처리
            return 0.5;
        }

        double sr0 = trials <= 1 ? 0.0 : expectedMaxSharpe(trials, trialsVariance);

        double numerator = (srObserved - sr0) * Math.sqrt(observations - 1);
        double varianceTerm = 1 - skew * srObserved + ((kurt - 1) / 4.0) * srObserved * srObserved;
        if (varianceTerm <= 0) {
            // 극단적인 왜도/첨도 조합에서는 이론상 분산이 음수로 나올 수 있다(표본 추정 오차) —
            // 0으로 나누는 사고를 막기 위한 수치적 방어. 실무적으로 이런 데이터는 애초에 신뢰하면 안 된다.
            varianceTerm = 1e-12;
        }
        double z = numerator / Math.sqrt(varianceTerm);
        return standardNormalCdf(z);
    }

    /**
     * OOS(walk-forward 최종 out-of-sample) 레벨에서 쓰는 DSR — 게이트①(OOS DSR &gt; 0.95)
     * 판정에는 <b>반드시</b> 이 메서드를 써야 한다.
     *
     * <h2>쉬운 설명 — N이 왜 "전략 계열 수"여야 하는가</h2>
     * walk-forward의 train 단계에서는 창마다 파라미터 후보 여러 개를 시도해 그중 최고를
     * "이미" 골랐다. 그 선택 과정의 다중검정 편향은 train 단계에서 {@link #deflatedSharpeRatio}
     * (trials=후보수×창수)로 이미 따로 다뤄진다. 하지만 그렇게 골라진 뒤 이어붙인 OOS
     * 수익률 시계열 자체는 "선택이 끝난 뒤의 단일 결과"다 — 그 시계열 하나만 놓고 보면
     * 더 이상 "여러 개 중 하나를 고르는" 다중검정이 남아있지 않다.
     *
     * <p>그런데 실제로 우리가 최종 판단(어떤 전략을 채택할지)을 내리는 시점에는 여러 개의
     * <b>전략 계열</b>(예: 무필터돌파 A, 필터돌파 B, 시계열모멘텀 C)의 OOS 성적을 나란히
     * 놓고 비교해서 그중 하나(또는 몇 개)를 고른다 — 바로 이 "계열 간 비교·선택"이 OOS
     * 레벨에서 진짜로 남아있는 다중검정 지점이다. 그래서 OOS 레벨 DSR의 N은 "OOS 성적을
     * 놓고 비교한 전략 계열의 수"여야 하고, trial 간 분산도 그 계열들의 OOS 샤프비율
     * 분산이어야 한다 — 파라미터 후보 수나 walk-forward 창 수와는 무관하다.
     *
     * @param oosDailyReturnsOfSelected   채택 여부를 판단하려는 전략의 OOS 일별 수익률 시계열
     *                                    (walk-forward로 이어붙인 것, {@link WalkForwardResult#oosResult()}
     *                                    의 {@code dailyReturns()}를 그대로 넘기면 된다)
     * @param allFamiliesOosSharpesDaily  비교 대상 전략 "계열"들의 OOS 일별(비연율화) 샤프비율.
     *                                    채택하려는 전략 자신의 값도 포함해서 넘겨야 한다 — 배열
     *                                    길이가 곧 N이다. N=1이면(비교할 다른 계열이 없으면)
     *                                    SR0=0으로 처리한다(비교로 인한 선택 편향이 없다는 뜻).
     * @return OOS 레벨 DSR (0~1)
     */
    public double deflatedSharpeAcrossFamilies(List<Double> oosDailyReturnsOfSelected,
                                                double[] allFamiliesOosSharpesDaily) {
        int observations = oosDailyReturnsOfSelected.size();
        double mean = mean(oosDailyReturnsOfSelected);
        double std = populationStd(oosDailyReturnsOfSelected, mean);
        double srDaily = std == 0.0 ? 0.0 : mean / std; // calculate()와 같은 컨벤션 — 일별(비연율화) 샤프
        double skew = skewness(oosDailyReturnsOfSelected, mean, std);
        double kurt = kurtosis(oosDailyReturnsOfSelected, mean, std);

        int families = allFamiliesOosSharpesDaily.length;
        double trialsVariance = families <= 1 ? 0.0 : populationVariance(allFamiliesOosSharpesDaily);

        return deflatedSharpeRatio(srDaily, families, trialsVariance, skew, kurt, observations);
    }

    /**
     * 시도를 N번 했을 때 "순전히 운으로" 기대할 수 있는 최대 샤프비율 SR0.
     * 극값이론(extreme value theory) 근사식 — N번의 시행 중 최댓값의 기댓값.
     */
    private double expectedMaxSharpe(int trials, double trialsVariance) {
        double term1 = (1 - EULER_MASCHERONI) * inverseStandardNormalCdf(1 - 1.0 / trials);
        double term2 = EULER_MASCHERONI * inverseStandardNormalCdf(1 - 1.0 / (trials * Math.E));
        return Math.sqrt(trialsVariance) * (term1 + term2);
    }

    private double mean(List<Double> values) {
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return values.isEmpty() ? 0.0 : sum / values.size();
    }

    /** 모표준편차(분모 n) — DSR 공식의 왜도/첨도 추정치와 같은 관례(분모 n)를 써야 서로 맞아떨어진다. */
    private double populationStd(List<Double> values, double mean) {
        if (values.isEmpty()) {
            return 0.0;
        }
        double sumSq = 0.0;
        for (double v : values) {
            double d = v - mean;
            sumSq += d * d;
        }
        return Math.sqrt(sumSq / values.size());
    }

    /** 모분산(분모 n) — {@link #deflatedSharpeAcrossFamilies}의 계열 간 샤프비율 분산 계산용. */
    private double populationVariance(double[] values) {
        if (values.length == 0) {
            return 0.0;
        }
        double mean = 0.0;
        for (double v : values) {
            mean += v;
        }
        mean /= values.length;

        double sumSq = 0.0;
        for (double v : values) {
            double d = v - mean;
            sumSq += d * d;
        }
        return sumSq / values.length;
    }

    /** 왜도(skewness) — 분포가 좌우 어느 쪽으로 치우쳤는지. */
    private double skewness(List<Double> values, double mean, double std) {
        if (values.isEmpty() || std == 0.0) {
            return 0.0;
        }
        double sumCube = 0.0;
        for (double v : values) {
            double d = v - mean;
            sumCube += d * d * d;
        }
        double m3 = sumCube / values.size();
        return m3 / Math.pow(std, 3);
    }

    /** 첨도(kurtosis, 비초과) — 정규분포면 3.0. DSR 공식은 (kurt-1)/4 항에서 이 값을 그대로 쓴다. */
    private double kurtosis(List<Double> values, double mean, double std) {
        if (values.isEmpty() || std == 0.0) {
            return 3.0; // 정규분포 기본값 — 데이터가 없으면 "정규분포로 가정"이 가장 무난한 기본값
        }
        double sumQuad = 0.0;
        for (double v : values) {
            double d = v - mean;
            sumQuad += d * d * d * d;
        }
        double m4 = sumQuad / values.size();
        return m4 / Math.pow(std, 4);
    }

    /**
     * 표준정규분포 누적분포함수 Φ(x).
     * erf(오차함수) 근사식 출처: Abramowitz &amp; Stegun, "Handbook of Mathematical Functions",
     * 식 7.1.26 (최대 오차 1.5×10⁻⁷) — Apache Commons Math 등 외부 의존성 없이 직접 구현.
     */
    private double standardNormalCdf(double x) {
        return 0.5 * (1.0 + erf(x / Math.sqrt(2)));
    }

    private double erf(double x) {
        // Abramowitz & Stegun 7.1.26
        double sign = x < 0 ? -1.0 : 1.0;
        x = Math.abs(x);
        double a1 = 0.254829592;
        double a2 = -0.284496736;
        double a3 = 1.421413741;
        double a4 = -1.453152027;
        double a5 = 1.061405429;
        double p = 0.3275911;

        double t = 1.0 / (1.0 + p * x);
        double y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * Math.exp(-x * x);
        return sign * y;
    }

    /**
     * 표준정규분포 역누적분포함수 Φ⁻¹(p) (probit 함수).
     * 출처: Peter J. Acklam, "An algorithm for computing the inverse normal cumulative
     * distribution function" (2003) — 유리함수 근사, 상대오차 약 1.15×10⁻⁹ 수준.
     */
    private double inverseStandardNormalCdf(double p) {
        if (p <= 0.0 || p >= 1.0) {
            throw new IllegalArgumentException("p는 (0,1) 구간이어야 함: " + p);
        }

        double[] a = {
                -3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
                1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00
        };
        double[] b = {
                -5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
                6.680131188771972e+01, -1.328068155288572e+01
        };
        double[] c = {
                -7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
                -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00
        };
        double[] d = {
                7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00,
                3.754408661907416e+00
        };

        double pLow = 0.02425;
        double pHigh = 1 - pLow;

        if (p < pLow) {
            double q = Math.sqrt(-2 * Math.log(p));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        } else if (p <= pHigh) {
            double q = p - 0.5;
            double r = q * q;
            return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
                    / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
        } else {
            double q = Math.sqrt(-2 * Math.log(1 - p));
            return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
    }
}

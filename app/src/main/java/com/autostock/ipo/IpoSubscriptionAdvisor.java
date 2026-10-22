package com.autostock.ipo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 공모주 청약 추천 — 수요예측 결과가 나온 뒤, 청약 전에 "비례까지 / 균등만 / 패스"를 정한다(2026-10-15 사용자 "공모주는 청약 기준
 * 추천 전략이 필요", aiDoc/ipo-subscription-strategy.md 1절 사전 선언). 계산만 한다 — 값 모으기는 {@link IpoSyncScheduler},
 * 과거 자료 검증은 시험 소스의 {@code IpoHistoryBacktest}가 <b>같은 코드</b>를 쓴다.
 *
 * <p>점수(판단 시점에 알 수 있는 값만 — 일반청약 경쟁률·상장일 시세는 쓰지 않는다):
 * <ul>
 *   <li>C 기관경쟁률: ≥1,000 +2 · ≥500 +1 · ≥300 0 · ≥100 −1 · &lt;100 −2</li>
 *   <li>L 의무보유확약(신청 수량 기준): 직전 {@value #LOCKUP_WINDOW}건 중앙값 m 이상이고 1% 이상 +1 · m의 절반 미만 −1 · 그 밖 0
 *       (직전 {@value #LOCKUP_MIN_SAMPLES}건 미만이면 0) — 2025-07 확약 우선배정 뒤 분포가 크게 움직여 상대값으로 본다</li>
 *   <li>B 밴드 위치: 상단 초과 +1 · 상단 0 · 밴드 안·하단 −1 · 하단 미만 −2 · 모름 0</li>
 *   <li>F 상장 직후 유통가능물량: ≤20% +1 · ≤30% 0 · ≤40% −1 · &gt;40% −2 · 모름 0</li>
 *   <li>M 상장 시가총액(상장예정주식수 × 확정가): ≥5,000억 −1</li>
 *   <li>S 분위기(직전 상장 {@value #MOOD_WINDOW}곳 상장일 종가 수익률 평균): ≥30% 0 · ≥0% −1 · &lt;0% −2 ·
 *       직전 {@value #MOOD_MIN_SAMPLES}곳 미만 0</li>
 * </ul>
 * 등급: C를 모르면 판단 보류 → 합 ≤ −2 또는 C &lt; 100이면 패스 → 합 ≥ 3·C ≥ 1,000·밴드 상단 이상·유통 ≤30%(모르면 통과)·S 0점이면
 * 비례까지 → 나머지 균등만. 임계값은 결과를 본 뒤 바꾸지 않는다(바꿀 때는 새 trial).
 */
final class IpoSubscriptionAdvisor {

    static final int LOCKUP_WINDOW = 20;
    static final int LOCKUP_MIN_SAMPLES = 5;
    static final int MOOD_WINDOW = 5;
    static final int MOOD_MIN_SAMPLES = 3;
    static final BigDecimal LARGE_MARKET_CAP = new BigDecimal("500000000000");   // 5,000억 원

    /** 등급 — 화면·알림 문구는 {@link #label}. */
    enum Grade {
        PROPORTIONAL("비례까지"),
        EQUAL_ONLY("균등만"),
        PASS("패스"),
        PENDING("판단 보류");

        final String label;

        Grade(String label) {
            this.label = label;
        }

        /** 기존 권고 칸(RECOMMEND/SKIP/PENDING)으로 옮긴 값 — 화면·API 호환. */
        IpoRecommendation recommendation() {
            return switch (this) {
                case PROPORTIONAL, EQUAL_ONLY -> IpoRecommendation.RECOMMEND;
                case PASS -> IpoRecommendation.SKIP;
                case PENDING -> IpoRecommendation.PENDING;
            };
        }
    }

    /**
     * 판단 시점의 시장 맥락 — 직전 딜들에서 센다.
     *
     * @param lockupMedian 직전 {@value #LOCKUP_WINDOW}건 확약비율 중앙값(0~1) — 표본이 모자라면 null
     * @param lockupSamples 그 표본 수
     * @param moodMean     직전 상장 {@value #MOOD_WINDOW}곳의 상장일 종가 수익률 평균(0.42 = +42%) — 모자라면 null
     * @param moodSamples  그 표본 수
     */
    record Context(BigDecimal lockupMedian, int lockupSamples, BigDecimal moodMean, int moodSamples) {
        static final Context NONE = new Context(null, 0, null, 0);

        /** 직전 값 목록으로 맥락을 만든다(오래된 것 → 최근 것 순서가 아니어도 된다 — 개수만 자른다). */
        static Context of(List<BigDecimal> recentLockups, List<BigDecimal> recentListingReturns) {
            List<BigDecimal> lockups = tail(recentLockups, LOCKUP_WINDOW);
            List<BigDecimal> moods = tail(recentListingReturns, MOOD_WINDOW);
            return new Context(lockups.size() >= LOCKUP_MIN_SAMPLES ? median(lockups) : null, lockups.size(),
                    moods.size() >= MOOD_MIN_SAMPLES ? mean(moods) : null, moods.size());
        }
    }

    /**
     * 판단 재료.
     *
     * @param competitionRate 기관경쟁률(N:1의 N)
     * @param lockupRate      의무보유확약비율(신청 수량 기준, 0~1)
     * @param finalPrice      확정 공모가
     * @param bandLow         희망 밴드 하단
     * @param bandHigh        희망 밴드 상단
     * @param floatRatio      상장 직후 유통가능물량 비율(0~1)
     * @param listedShares    상장예정주식수
     */
    record Input(BigDecimal competitionRate, BigDecimal lockupRate, BigDecimal finalPrice, BigDecimal bandLow,
                 BigDecimal bandHigh, BigDecimal floatRatio, Long listedShares, Context context) {
    }

    /** 점수 항목 하나 — 화면·알림에 근거로 그대로 싣는다. */
    record Factor(String code, String text, int points) {
        /** "기관 1,157.98:1(+2)", 괄호로 끝나면 그 안에 — "확약 23.83%(직전 20건 중앙값 12.00% 이상, +1)". */
        String describe() {
            if (points == 0) {
                return text;
            }
            String p = "%+d".formatted(points);
            return text.endsWith(")") ? text.substring(0, text.length() - 1) + ", " + p + ")" : text + "(" + p + ")";
        }
    }

    /**
     * @param grade   등급
     * @param score   점수 합
     * @param factors 항목별 근거
     * @param method  청약 방법 한 줄
     */
    record Advice(Grade grade, int score, List<Factor> factors, String method) {

        /** "균등만(점수 +1) — 기관 983.98:1(+1) · 확약 13.31%(직전 20건 중앙값 4.10% 이상, +1) · …. 청약: …" */
        String reason() {
            String head = grade == Grade.PENDING ? grade.label : "%s(점수 %+d)".formatted(grade.label, score);
            return head + " — " + factors.stream().map(Factor::describe).collect(Collectors.joining(" · "))
                    + (method == null ? "" : ". " + method);
        }
    }

    private IpoSubscriptionAdvisor() {
    }

    static Advice advise(Input in) {
        List<Factor> factors = new ArrayList<>();
        if (in.competitionRate() == null || in.competitionRate().signum() <= 0) {
            factors.add(new Factor("C", "기관경쟁률 미확인 — [발행조건확정] 수요예측 결과가 나오면 판단", 0));
            return new Advice(Grade.PENDING, 0, factors, null);
        }
        BigDecimal c = in.competitionRate();
        int cPoints = c.compareTo(BigDecimal.valueOf(1000)) >= 0 ? 2
                : c.compareTo(BigDecimal.valueOf(500)) >= 0 ? 1
                : c.compareTo(BigDecimal.valueOf(300)) >= 0 ? 0
                : c.compareTo(BigDecimal.valueOf(100)) >= 0 ? -1 : -2;
        factors.add(new Factor("C", "기관 " + IpoDemandForecastCollector.rateText(c), cPoints));

        Context ctx = in.context() == null ? Context.NONE : in.context();
        int lPoints = 0;
        String lText;
        if (in.lockupRate() == null) {
            lText = "확약 미확인";
        } else if (ctx.lockupMedian() == null) {
            lText = "확약 %s(직전 비교 표본 %d건 — 점수 없음)".formatted(pct(in.lockupRate()), ctx.lockupSamples());
        } else {
            BigDecimal m = ctx.lockupMedian();
            if (in.lockupRate().compareTo(m) >= 0 && in.lockupRate().compareTo(new BigDecimal("0.01")) >= 0) {
                lPoints = 1;
                lText = "확약 %s(직전 %d건 중앙값 %s 이상)".formatted(pct(in.lockupRate()), ctx.lockupSamples(), pct(m));
            } else if (in.lockupRate().compareTo(m.divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP)) < 0) {
                lPoints = -1;
                lText = "확약 %s(직전 %d건 중앙값 %s의 절반 미만)".formatted(pct(in.lockupRate()), ctx.lockupSamples(), pct(m));
            } else {
                lText = "확약 %s(직전 %d건 중앙값 %s 근처)".formatted(pct(in.lockupRate()), ctx.lockupSamples(), pct(m));
            }
        }
        factors.add(new Factor("L", lText, lPoints));

        String band = in.finalPrice() == null ? null
                : new OfferingPriceParser.OfferingPrice(in.finalPrice(), in.bandLow(), in.bandHigh()).positionInBand();
        int bPoints = band == null ? 0 : switch (band) {
            case "상단 초과" -> 1;
            case "상단" -> 0;
            case "하단 미만" -> -2;
            default -> -1;   // 밴드 안·하단
        };
        factors.add(new Factor("B", band == null ? "밴드 위치 미확인" : "확정가 밴드 " + band, bPoints));

        int fPoints = 0;
        String fText;
        if (in.floatRatio() == null) {
            fText = "유통가능물량 미확인";
        } else {
            BigDecimal f = in.floatRatio();
            fPoints = f.compareTo(new BigDecimal("0.20")) <= 0 ? 1
                    : f.compareTo(new BigDecimal("0.30")) <= 0 ? 0
                    : f.compareTo(new BigDecimal("0.40")) <= 0 ? -1 : -2;
            fText = "유통가능 " + pct(f);
        }
        factors.add(new Factor("F", fText, fPoints));

        int mPoints = 0;
        if (in.listedShares() != null && in.finalPrice() != null) {
            BigDecimal cap = in.finalPrice().multiply(BigDecimal.valueOf(in.listedShares()));
            if (cap.compareTo(LARGE_MARKET_CAP) >= 0) {
                mPoints = -1;
                factors.add(new Factor("M", "상장 시총 약 %s억 원(5,000억 이상)".formatted(eok(cap)), mPoints));
            }
        }

        int sPoints = 0;
        String sText;
        if (ctx.moodMean() == null) {
            sText = "분위기 판단 표본 부족(직전 상장 %d곳)".formatted(ctx.moodSamples());
        } else {
            BigDecimal mood = ctx.moodMean();
            sPoints = mood.compareTo(new BigDecimal("0.30")) >= 0 ? 0 : mood.signum() >= 0 ? -1 : -2;
            sText = "직전 상장 %d곳 상장일 종가 평균 %s".formatted(ctx.moodSamples(), signedPct(mood));
        }
        factors.add(new Factor("S", sText, sPoints));

        int score = cPoints + lPoints + bPoints + fPoints + mPoints + sPoints;
        Grade grade;
        if (score <= -2 || c.compareTo(BigDecimal.valueOf(100)) < 0) {
            grade = Grade.PASS;
        } else if (score >= 3 && c.compareTo(BigDecimal.valueOf(1000)) >= 0
                && ("상단".equals(band) || "상단 초과".equals(band))
                && (in.floatRatio() == null || in.floatRatio().compareTo(new BigDecimal("0.30")) <= 0)
                && sPoints == 0) {
            grade = Grade.PROPORTIONAL;
        } else {
            grade = Grade.EQUAL_ONLY;
        }
        return new Advice(grade, score, List.copyOf(factors), method(grade, in.finalPrice()));
    }

    /** 청약 방법 한 줄 — 최소 단위는 보통 10주(증권사·종목마다 다를 수 있다), 증거금률 50%. */
    static String method(Grade grade, BigDecimal finalPrice) {
        String minimum = finalPrice == null ? "최소 단위(보통 10주)"
                : "최소 단위(보통 10주, 증거금 약 %s원)".formatted(won(finalPrice.multiply(BigDecimal.valueOf(5))));
        return switch (grade) {
            case PROPORTIONAL -> "청약: 균등 " + minimum + " + 비례 추가(증거금은 정한 상한 안에서, 받을 주수 ≈ 청약 주수 ÷ 비례 경쟁률). "
                    + "증권사 한 곳만(중복청약 금지). 상장일 시초가 매도";
            case EQUAL_ONLY -> "청약: 균등 " + minimum + "만. 증권사 한 곳만. 상장일 시초가 매도";
            case PASS -> "청약하지 않음";
            case PENDING -> null;
        };
    }

    static String pct(BigDecimal ratio) {
        return ratio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static String signedPct(BigDecimal ratio) {
        BigDecimal p = ratio.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP);
        return (p.signum() > 0 ? "+" : "") + p.toPlainString() + "%";
    }

    private static String eok(BigDecimal won) {
        return String.format(Locale.ROOT, "%,d", won.divide(new BigDecimal("100000000"), 0, RoundingMode.HALF_UP).longValue());
    }

    private static String won(BigDecimal value) {
        return String.format(Locale.ROOT, "%,d", value.setScale(0, RoundingMode.HALF_UP).longValue());
    }

    private static List<BigDecimal> tail(List<BigDecimal> values, int n) {
        List<BigDecimal> clean = values == null ? List.of() : values.stream().filter(v -> v != null).toList();
        return clean.size() <= n ? clean : clean.subList(clean.size() - n, clean.size());
    }

    static BigDecimal median(List<BigDecimal> values) {
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2)
                : sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
    }

    static BigDecimal mean(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }
}

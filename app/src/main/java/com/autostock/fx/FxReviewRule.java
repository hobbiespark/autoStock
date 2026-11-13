package com.autostock.fx;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * 직접매수 검토 알림 판정 — 설계 6장 C1~C6(D-28). 프레임워크·DB를 모르는 순수 규칙이다(VALUNIVO K-23).
 *
 * <p>이 규칙은 "데브가 정한 조건에 닿았는가"만 계산한다. 매수 여부는 데브가 은행 앱의 실제 환율을 보고 정한다(설계 FX-0b).
 *
 * <ul>
 *   <li>기준선 = (신청 당시 환율 + 예약 환율) ÷ 2. 반올림 없이 비교한다(÷2는 유한 소수라 정확).</li>
 *   <li>추정 매수 환율 = ECOS 최신값 + 그 예약의 차이(설계 4장). 둘 중 하나라도 없으면 판정하지 않는다.</li>
 *   <li>C6 중복 방지: 같은 예약에는 추정 환율이 기준선 아래로 <b>새로</b> 내려왔을 때만(처음 판정이 아래면 그때 한 번),
 *       그리고 그 통화로 오늘 보낸 검토 알림이 없을 때만 보낸다.</li>
 *   <li>"앞 판정이 아래였는가"는 검토 대상(C1·C2·C3·C5 충족)일 때만 갱신한다. 대상이 아니면 비운다 — 15일 전부터 아래였다는
 *       이유로 15일 첫 알림이 막히지 않게 한다.</li>
 * </ul>
 */
public final class FxReviewRule {

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    /** 판정 결과의 이유 — 로그·시험에서 어느 조건에서 멈췄는지 본다. */
    public enum Reason {
        SEND,
        BEFORE_REVIEW_DAY,     // C1
        NOT_OPEN,              // C2
        ALREADY_BOUGHT,        // C3
        OVER_BUDGET,           // C5
        NO_RATE,               // 추정 불가
        ABOVE_BASELINE,        // C4
        STILL_BELOW,           // C6: 이미 알린 뒤 계속 아래
        SENT_TODAY             // C6: 그 통화로 오늘 이미 보냄
    }

    /**
     * 판정 입력.
     *
     * @param today              오늘(KST)
     * @param reviewStartDay     검토 시작일(포함)
     * @param open               기준 예약이 열려 있는가(체결 입력 없음)
     * @param requestRate        신청 당시 은행 환율
     * @param reserveRate        예약 환율
     * @param spread             그 예약의 은행-공개 환율 차이(없으면 {@code null})
     * @param publicRate         ECOS 최신값(없으면 {@code null})
     * @param lastBelow          앞 판정에서 기준선 아래였는가(없으면 {@code null})
     * @param boughtThisMonth    이번 달 그 통화 직접매수가 있는가
     * @param exposureKrw        이번 달 노출액({@link FxExposure#of})
     * @param directBuyKrw       그 통화 직접매수 금액(설정)
     * @param monthlyCapKrw      월 상한(설정)
     * @param sentTodayForCurrency 그 통화로 오늘 검토 알림을 보냈는가
     */
    public record Input(LocalDate today, int reviewStartDay, boolean open,
                        BigDecimal requestRate, BigDecimal reserveRate, BigDecimal spread, BigDecimal publicRate,
                        Boolean lastBelow, boolean boughtThisMonth,
                        long exposureKrw, long directBuyKrw, long monthlyCapKrw, boolean sentTodayForCurrency) {
        public Input {
            Objects.requireNonNull(today);
            Objects.requireNonNull(requestRate);
            Objects.requireNonNull(reserveRate);
        }
    }

    /**
     * 판정 결과.
     *
     * @param send          검토 알림을 보낸다
     * @param reason        멈춘 조건 또는 {@link Reason#SEND}
     * @param nextLastBelow 예약에 저장할 다음 상태
     * @param baseline      기준선
     * @param estimate      추정 매수 환율(없으면 {@code null})
     */
    public record Result(boolean send, Reason reason, Boolean nextLastBelow, BigDecimal baseline, BigDecimal estimate) {}

    private FxReviewRule() {
    }

    /** 기준선 = (신청 당시 + 예약) ÷ 2. */
    public static BigDecimal baseline(BigDecimal requestRate, BigDecimal reserveRate) {
        return requestRate.add(reserveRate).divide(TWO);
    }

    public static Result evaluate(Input in) {
        BigDecimal baseline = baseline(in.requestRate(), in.reserveRate());
        BigDecimal estimate = (in.spread() == null || in.publicRate() == null) ? null : in.publicRate().add(in.spread());

        Reason ineligible = ineligibleReason(in);
        if (ineligible != null) {
            return new Result(false, ineligible, null, baseline, estimate);
        }
        if (estimate == null) {
            return new Result(false, Reason.NO_RATE, in.lastBelow(), baseline, null);
        }
        boolean below = estimate.compareTo(baseline) <= 0;
        if (!below) {
            return new Result(false, Reason.ABOVE_BASELINE, false, baseline, estimate);
        }
        if (Boolean.TRUE.equals(in.lastBelow())) {
            return new Result(false, Reason.STILL_BELOW, true, baseline, estimate);
        }
        if (in.sentTodayForCurrency()) {
            // 새로 내려왔지만 오늘 이미 보냄 — 상태를 바꾸지 않아 다음 날 같은 조건이면 보낸다.
            return new Result(false, Reason.SENT_TODAY, in.lastBelow(), baseline, estimate);
        }
        return new Result(true, Reason.SEND, true, baseline, estimate);
    }

    private static Reason ineligibleReason(Input in) {
        if (in.today().getDayOfMonth() < in.reviewStartDay()) {
            return Reason.BEFORE_REVIEW_DAY;
        }
        if (!in.open()) {
            return Reason.NOT_OPEN;
        }
        if (in.boughtThisMonth()) {
            return Reason.ALREADY_BOUGHT;
        }
        if (FxExposure.exceedsCap(in.exposureKrw(), in.directBuyKrw(), in.monthlyCapKrw())) {
            return Reason.OVER_BUDGET;
        }
        return null;
    }

    /**
     * 예약 입력 검사(설계 6장·9장). 문제가 없으면 비어 있다.
     * 예약 환율이 신청 당시 환율보다 낮아야 기준선이 신청 당시보다 낮아져 "신청 당시보다 하락" 조건이 C4에 들어간다(F-2).
     */
    public static Optional<String> reservationProblem(BigDecimal requestRate, BigDecimal reserveRate, long amountKrw) {
        if (requestRate == null || reserveRate == null || requestRate.signum() <= 0 || reserveRate.signum() <= 0) {
            return Optional.of("환율은 0보다 커야 합니다");
        }
        if (amountKrw <= 0) {
            return Optional.of("금액은 0원보다 커야 합니다");
        }
        if (reserveRate.compareTo(requestRate) >= 0) {
            return Optional.of("예약 환율은 신청 당시 환율보다 낮아야 합니다");
        }
        return Optional.empty();
    }
}

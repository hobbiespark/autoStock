package com.autostock.fx;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 설계 6장 C1~C6(aiDoc/fx-accumulation-alert.md, D-28). 값은 모두 합성이다.
 * 기준 예: 신청 당시 1360, 예약 1330 → 기준선 1345. 차이 14.5 → 공개 1330.5면 추정 1345(경계).
 */
class FxReviewRuleTest {

    private static final LocalDate DAY_15 = LocalDate.of(2026, 12, 15);

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    /** 모든 조건이 맞는 기본 입력 — 시험마다 하나만 바꾼다. */
    private static FxReviewRule.Input base(LocalDate today, String publicRate, Boolean lastBelow) {
        return new FxReviewRule.Input(today, 15, true, d("1360"), d("1330"), d("14.5"),
                publicRate == null ? null : d(publicRate), lastBelow, false, 100_000, 20_000, 130_000, false);
    }

    @Test
    void 기준선은_신청_당시와_예약_환율의_반올림_없는_중간값이다() {
        assertEquals(0, d("1345").compareTo(FxReviewRule.baseline(d("1360"), d("1330"))));
        assertEquals(0, d("1345.00005").compareTo(FxReviewRule.baseline(d("1360.0001"), d("1330"))));
    }

    @Test
    void 조건이_모두_맞으면_보내고_다음_상태는_아래다() {
        FxReviewRule.Result r = FxReviewRule.evaluate(base(DAY_15, "1330", null));

        assertTrue(r.send());
        assertEquals(FxReviewRule.Reason.SEND, r.reason());
        assertEquals(Boolean.TRUE, r.nextLastBelow());
        assertEquals(0, d("1344.5").compareTo(r.estimate()));
    }

    @Test
    void 추정_환율이_기준선과_같으면_보낸다() {
        assertTrue(FxReviewRule.evaluate(base(DAY_15, "1330.5", null)).send());
    }

    @Test
    void 추정_환율이_기준선보다_조금이라도_높으면_보내지_않고_상태는_위다() {
        FxReviewRule.Result r = FxReviewRule.evaluate(base(DAY_15, "1330.5001", null));

        assertFalse(r.send());
        assertEquals(FxReviewRule.Reason.ABOVE_BASELINE, r.reason());
        assertEquals(Boolean.FALSE, r.nextLastBelow());
    }

    @Test
    void 검토_시작일_전날은_대상이_아니고_상태를_비운다() {
        FxReviewRule.Result r = FxReviewRule.evaluate(base(DAY_15.minusDays(1), "1300", true));

        assertFalse(r.send());
        assertEquals(FxReviewRule.Reason.BEFORE_REVIEW_DAY, r.reason());
        assertNull(r.nextLastBelow());
    }

    @Test
    void 시작일_전부터_아래였어도_시작일_첫_판정에서_보낸다() {
        // 14일 판정이 상태를 비우므로 15일에는 lastBelow=null로 들어온다.
        Boolean after14 = FxReviewRule.evaluate(base(DAY_15.minusDays(1), "1300", null)).nextLastBelow();

        assertTrue(FxReviewRule.evaluate(base(DAY_15, "1300", after14)).send());
    }

    @Test
    void 이미_알린_뒤_계속_아래면_다시_보내지_않는다() {
        FxReviewRule.Result r = FxReviewRule.evaluate(base(DAY_15.plusDays(1), "1300", true));

        assertFalse(r.send());
        assertEquals(FxReviewRule.Reason.STILL_BELOW, r.reason());
        assertEquals(Boolean.TRUE, r.nextLastBelow());
    }

    @Test
    void 위로_올라갔다가_다시_내려오면_다시_보낸다() {
        Boolean afterAbove = FxReviewRule.evaluate(base(DAY_15.plusDays(1), "1340", true)).nextLastBelow();

        assertEquals(Boolean.FALSE, afterAbove);
        assertTrue(FxReviewRule.evaluate(base(DAY_15.plusDays(2), "1300", afterAbove)).send());
    }

    @Test
    void 그_통화로_오늘_이미_보냈으면_보내지_않고_상태를_바꾸지_않는다() {
        FxReviewRule.Input sentToday = new FxReviewRule.Input(DAY_15, 15, true, d("1360"), d("1330"), d("14.5"),
                d("1300"), false, false, 100_000, 20_000, 130_000, true);

        FxReviewRule.Result r = FxReviewRule.evaluate(sentToday);

        assertFalse(r.send());
        assertEquals(FxReviewRule.Reason.SENT_TODAY, r.reason());
        assertEquals(Boolean.FALSE, r.nextLastBelow());
    }

    @Test
    void 체결된_예약이면_보내지_않는다() {
        FxReviewRule.Input filled = new FxReviewRule.Input(DAY_15, 15, false, d("1360"), d("1330"), d("14.5"),
                d("1300"), null, false, 100_000, 20_000, 130_000, false);

        assertEquals(FxReviewRule.Reason.NOT_OPEN, FxReviewRule.evaluate(filled).reason());
    }

    @Test
    void 이번_달_직접매수가_있으면_보내지_않는다() {
        FxReviewRule.Input bought = new FxReviewRule.Input(DAY_15, 15, true, d("1360"), d("1330"), d("14.5"),
                d("1300"), null, true, 100_000, 20_000, 130_000, false);

        assertEquals(FxReviewRule.Reason.ALREADY_BOUGHT, FxReviewRule.evaluate(bought).reason());
    }

    @Test
    void 노출액과_직접매수_합이_상한과_같으면_보내고_넘으면_보내지_않는다() {
        FxReviewRule.Input atCap = new FxReviewRule.Input(DAY_15, 15, true, d("1360"), d("1330"), d("14.5"),
                d("1300"), null, false, 110_000, 20_000, 130_000, false);
        FxReviewRule.Input overCap = new FxReviewRule.Input(DAY_15, 15, true, d("1360"), d("1330"), d("14.5"),
                d("1300"), null, false, 110_001, 20_000, 130_000, false);

        assertTrue(FxReviewRule.evaluate(atCap).send());
        assertEquals(FxReviewRule.Reason.OVER_BUDGET, FxReviewRule.evaluate(overCap).reason());
    }

    @Test
    void 공개_환율이나_차이가_없으면_판정하지_않고_상태를_유지한다() {
        FxReviewRule.Result noPublic = FxReviewRule.evaluate(base(DAY_15, null, true));
        FxReviewRule.Input noSpread = new FxReviewRule.Input(DAY_15, 15, true, d("1360"), d("1330"), null,
                d("1300"), false, false, 100_000, 20_000, 130_000, false);

        assertEquals(FxReviewRule.Reason.NO_RATE, noPublic.reason());
        assertEquals(Boolean.TRUE, noPublic.nextLastBelow());
        assertEquals(Boolean.FALSE, FxReviewRule.evaluate(noSpread).nextLastBelow());
    }

    @Test
    void 엔화도_원_100엔_단위_그대로_같은_규칙이다() {
        FxReviewRule.Input jpy = new FxReviewRule.Input(DAY_15, 15, true, d("905.20"), d("890.00"), d("12.30"),
                d("885.00"), null, false, 0, 20_000, 130_000, false);

        FxReviewRule.Result r = FxReviewRule.evaluate(jpy);

        assertTrue(r.send());                                   // 추정 897.30 ≤ 기준선 897.60
        assertEquals(0, d("897.6").compareTo(r.baseline()));
    }

    @Test
    void 예약_입력은_예약_환율이_신청_당시보다_낮아야_한다() {
        assertTrue(FxReviewRule.reservationProblem(d("1360"), d("1330"), 40_000).isEmpty());
        assertTrue(FxReviewRule.reservationProblem(d("1360"), d("1360"), 40_000).isPresent());
        assertTrue(FxReviewRule.reservationProblem(d("1360"), d("1370"), 40_000).isPresent());
        assertTrue(FxReviewRule.reservationProblem(d("1360"), d("1330"), 0).isPresent());
        assertTrue(FxReviewRule.reservationProblem(d("0"), d("-1"), 40_000).isPresent());
    }
}

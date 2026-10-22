package com.autostock.ipo;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 청약 추천 — aiDoc/ipo-subscription-strategy.md 1절 사전 선언의 점수·등급을 그대로 고정한다(결과를 본 뒤 바꾸지 않는다).
 */
class IpoSubscriptionAdvisorTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    /** 확약 중앙값 10%(직전 20건), 분위기 +50%(직전 5곳). */
    private static final IpoSubscriptionAdvisor.Context WARM = new IpoSubscriptionAdvisor.Context(d("0.10"), 20, d("0.50"), 5);

    private static IpoSubscriptionAdvisor.Advice advise(String competition, String lockup, String finalPrice, String low,
                                                        String high, String floatRatio, Long listed,
                                                        IpoSubscriptionAdvisor.Context ctx) {
        return IpoSubscriptionAdvisor.advise(new IpoSubscriptionAdvisor.Input(
                competition == null ? null : d(competition), lockup == null ? null : d(lockup),
                finalPrice == null ? null : d(finalPrice), low == null ? null : d(low), high == null ? null : d(high),
                floatRatio == null ? null : d(floatRatio), listed, ctx));
    }

    private static int points(IpoSubscriptionAdvisor.Advice advice, String code) {
        return advice.factors().stream().filter(f -> f.code().equals(code)).findFirst().orElseThrow().points();
    }

    @Test
    void 기관경쟁률을_모르면_판단_보류() {
        IpoSubscriptionAdvisor.Advice advice = advise(null, "0.3", "10000", "9000", "10000", "0.2", null, WARM);
        assertEquals(IpoSubscriptionAdvisor.Grade.PENDING, advice.grade());
        assertNull(advice.method());
        assertEquals(IpoRecommendation.PENDING, advice.grade().recommendation());
    }

    @Test
    void 강한_딜은_비례까지() {
        // 아이티켐 꼴: 1,157:1, 확약 23.8%(중앙값 12% 이상), 상단, 유통 15.4%, 분위기 +62%
        IpoSubscriptionAdvisor.Advice advice = advise("1157.98", "0.2383", "16100", "13000", "16100", "0.1544", 12_583_000L,
                new IpoSubscriptionAdvisor.Context(d("0.12"), 20, d("0.62"), 5));
        assertEquals(IpoSubscriptionAdvisor.Grade.PROPORTIONAL, advice.grade());
        assertEquals(4, advice.score());
        assertEquals(IpoRecommendation.RECOMMEND, advice.grade().recommendation());
        assertTrue(advice.reason().startsWith("비례까지(점수 +4) — 기관 1,157.98:1(+2) · 확약 23.83%(직전 20건 중앙값 12.00% 이상, +1) · "
                + "확정가 밴드 상단 · 유통가능 15.44%(+1) · 직전 상장 5곳 상장일 종가 평균 +62.0%."), advice.reason());
        assertTrue(advice.method().startsWith("청약: 균등 최소 단위(보통 10주, 증거금 약 80,500원) + 비례 추가"), advice.method());
    }

    @Test
    void 점수가_높아도_분위기가_식었거나_유통이_많거나_밴드_상단이_아니면_비례는_아니다() {
        IpoSubscriptionAdvisor.Context cool = new IpoSubscriptionAdvisor.Context(d("0.10"), 20, d("0.20"), 5);
        assertEquals(IpoSubscriptionAdvisor.Grade.EQUAL_ONLY,
                advise("1200", "0.30", "10000", "8000", "10000", "0.15", null, cool).grade(), "분위기 +20% → −1");
        assertEquals(IpoSubscriptionAdvisor.Grade.EQUAL_ONLY,
                advise("1200", "0.30", "10000", "8000", "10000", "0.31", null, WARM).grade(), "유통 31%");
        assertEquals(IpoSubscriptionAdvisor.Grade.EQUAL_ONLY,
                advise("1200", "0.30", "9000", "8000", "10000", "0.15", null, WARM).grade(), "밴드 안");
        assertEquals(IpoSubscriptionAdvisor.Grade.EQUAL_ONLY,
                advise("999", "0.30", "10000", "8000", "10000", "0.15", null, WARM).grade(), "기관 1,000:1 미만");
    }

    @Test
    void 기관경쟁률이_100대_1_미만이면_다른_점수와_상관없이_패스() {
        IpoSubscriptionAdvisor.Advice advice = advise("99.9", "0.50", "10000", "8000", "10000", "0.10", null, WARM);
        assertEquals(-2 + 1 + 0 + 1, advice.score());
        assertEquals(IpoSubscriptionAdvisor.Grade.PASS, advice.grade());
        assertEquals("청약하지 않음", advice.method());
        assertEquals(IpoRecommendation.SKIP, advice.grade().recommendation());
    }

    @Test
    void 점수_합이_마이너스_2_이하면_패스() {
        // 니어스랩 꼴: 743:1(+1), 확약 1.26%(중앙값 10%의 절반 미만 −1), 상단(0), 유통 37.15%(−1), 분위기 −9%(−2) → −3
        IpoSubscriptionAdvisor.Advice advice = advise("743.49", "0.0126", "41200", "35000", "41200", "0.3715", null,
                new IpoSubscriptionAdvisor.Context(d("0.10"), 20, d("-0.09"), 5));
        assertEquals(-3, advice.score());
        assertEquals(IpoSubscriptionAdvisor.Grade.PASS, advice.grade());
        assertEquals(-2, points(advice, "S"));
        assertEquals(-1, points(advice, "L"));
    }

    @Test
    void 점수_경계() {
        // C: 1000 +2 · 500 +1 · 300 0 · 100 −1 · 99 −2
        assertEquals(2, points(advise("1000", null, null, null, null, null, null, null), "C"));
        assertEquals(1, points(advise("500", null, null, null, null, null, null, null), "C"));
        assertEquals(0, points(advise("300", null, null, null, null, null, null, null), "C"));
        assertEquals(-1, points(advise("100", null, null, null, null, null, null, null), "C"));
        // B: 상단 초과 +1 · 하단 −1 · 하단 미만 −2
        assertEquals(1, points(advise("500", null, "11000", "8000", "10000", null, null, null), "B"));
        assertEquals(-1, points(advise("500", null, "8000", "8000", "10000", null, null, null), "B"));
        assertEquals(-2, points(advise("500", null, "7000", "8000", "10000", null, null, null), "B"));
        // F: 20% +1 · 30% 0 · 40% −1 · 40% 초과 −2
        assertEquals(1, points(advise("500", null, null, null, null, "0.20", null, null), "F"));
        assertEquals(0, points(advise("500", null, null, null, null, "0.30", null, null), "F"));
        assertEquals(-1, points(advise("500", null, null, null, null, "0.40", null, null), "F"));
        assertEquals(-2, points(advise("500", null, null, null, null, "0.4001", null, null), "F"));
        // L: 중앙값 이상 +1, 절반 미만 −1, 그 사이 0, 1% 미만은 중앙값 이상이어도 +1 아님
        IpoSubscriptionAdvisor.Context low = new IpoSubscriptionAdvisor.Context(d("0.005"), 20, null, 0);
        assertEquals(1, points(advise("500", "0.10", null, null, null, null, null, WARM), "L"));
        assertEquals(0, points(advise("500", "0.06", null, null, null, null, null, WARM), "L"));
        assertEquals(-1, points(advise("500", "0.049", null, null, null, null, null, WARM), "L"));
        assertEquals(0, points(advise("500", "0.008", null, null, null, null, null, low), "L"));
        // S: 30% 0 · 0% −1 · 마이너스 −2
        assertEquals(0, points(advise("500", null, null, null, null, null, null, new IpoSubscriptionAdvisor.Context(null, 0, d("0.30"), 5)), "S"));
        assertEquals(-1, points(advise("500", null, null, null, null, null, null, new IpoSubscriptionAdvisor.Context(null, 0, d("0"), 5)), "S"));
        assertEquals(-2, points(advise("500", null, null, null, null, null, null, new IpoSubscriptionAdvisor.Context(null, 0, d("-0.01"), 5)), "S"));
    }

    @Test
    void 상장_시가총액_5천억_이상은_감점() {
        IpoSubscriptionAdvisor.Advice big = advise("500", null, "50000", null, null, null, 10_000_000L, null);
        assertEquals(-1, points(big, "M"));
        assertTrue(big.reason().contains("상장 시총 약 5,000억 원(5,000억 이상, -1)"), big.reason());
        IpoSubscriptionAdvisor.Advice small = advise("500", null, "49999", null, null, null, 10_000_000L, null);
        assertTrue(small.factors().stream().noneMatch(f -> f.code().equals("M")));
    }

    @Test
    void 맥락은_표본이_모자라면_비워_둔다() {
        IpoSubscriptionAdvisor.Context few = IpoSubscriptionAdvisor.Context.of(
                List.of(d("0.1"), d("0.2"), d("0.3"), d("0.4")), List.of(d("0.5"), d("-0.1")));
        assertNull(few.lockupMedian());
        assertNull(few.moodMean());
        assertEquals(4, few.lockupSamples());

        IpoSubscriptionAdvisor.Context ok = IpoSubscriptionAdvisor.Context.of(
                List.of(d("0.9"), d("0.1"), d("0.2"), d("0.3"), d("0.4"), d("0.5")),   // 마지막 20건(전부) 중앙값 0.35
                List.of(d("5.0"), d("0.5"), d("-0.1"), d("0.2"), d("0.1"), d("0.3")));   // 마지막 5곳: 0.5·−0.1·0.2·0.1·0.3
        assertEquals(0, d("0.35").compareTo(ok.lockupMedian()));
        assertEquals(0, d("0.2").compareTo(ok.moodMean()));
        assertEquals(5, ok.moodSamples());
    }
}

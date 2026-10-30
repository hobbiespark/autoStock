package com.autostock.ipo;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IpoSubscriptionSizer — 청약 금액 추천(2026-10-30 사용자 "청약추천이라면 200만원 예산 내에서 얼마나 청약할지도 추천").
 */
class IpoSubscriptionSizerTest {

    private static final LocalDate MON = LocalDate.of(2026, 11, 2);
    private final IpoSubscriptionSizer sizer = new IpoSubscriptionSizer(IpoSubscriptionProperties.DEFAULT);

    private static IpoSubscriptionSizer.Deal deal(String code, String grade, String price, LocalDate start, LocalDate end) {
        return IpoSubscriptionSizer.Deal.of(code, grade, new BigDecimal(price), null, null, start, end).orElseThrow();
    }

    @Test
    void 균등만은_최소_청약_수량과_그_증거금() {
        IpoSubscriptionSizer.Deal target = deal("A", "EQUAL_ONLY", "23500", MON, MON.plusDays(1));

        IpoSubscriptionSizer.Sizing sizing = sizer.size(target, List.of(target));

        assertEquals(10, sizing.qty());
        assertEquals(new BigDecimal("117500"), sizing.margin());
        assertEquals("추천 청약: 10주 · 증거금 약 117,500원(예산 200만 원 중) — 균등 최소 수량. 확정가 23,500원 기준 · "
                + "증거금률 50%·최소 10주·10주 단위 가정, 주관사 공고 확인.", sizer.text(sizing));
    }

    @Test
    void 비례까지는_겹치는_딜의_최소_증거금을_빼고_남은_예산을_비례_딜끼리_나눠_단위로_내린다() {
        IpoSubscriptionSizer.Deal target = deal("A", "PROPORTIONAL", "20000", MON, MON.plusDays(1));
        IpoSubscriptionSizer.Deal equal = deal("B", "EQUAL_ONLY", "10000", MON.plusDays(1), MON.plusDays(2));   // 하루 겹침
        IpoSubscriptionSizer.Deal otherProportional = deal("C", "PROPORTIONAL", "30000", MON, MON.plusDays(1));
        IpoSubscriptionSizer.Deal apart = deal("D", "EQUAL_ONLY", "50000", MON.plusDays(3), MON.plusDays(4));   // 겹치지 않음
        List<IpoSubscriptionSizer.Deal> all = List.of(target, equal, otherProportional, apart);

        IpoSubscriptionSizer.Sizing a = sizer.size(target, all);
        IpoSubscriptionSizer.Sizing c = sizer.size(otherProportional, all);

        // 겹치는 몫 50,000 + 150,000 = 200,000, 자기 최소 100,000 → 남은 1,700,000 ÷ 비례 2곳 = 850,000 → 1만 원 × 10주 단위로 8단위
        assertEquals(90, a.qty());
        assertEquals(new BigDecimal("900000"), a.margin());
        assertEquals(new BigDecimal("200000"), a.reservedOthers());
        assertEquals(2, a.overlapping());
        assertEquals(2, a.sharedWith());
        assertEquals(60, c.qty());
        assertTrue(a.margin().add(c.margin()).add(new BigDecimal("50000")).compareTo(new BigDecimal("2000000")) <= 0,
                "겹치는 세 딜 증거금 합이 예산 안");
        assertTrue(sizer.text(a).contains("비례: 청약이 겹치는 딜 2곳 최소 증거금 200,000원을 뺀 나머지, 비례 딜 2곳이 나눔"), sizer.text(a));
    }

    @Test
    void 스팩은_겹치는_딜_몫을_뺀_남은_예산_안의_비례_청약이고_근거를_남긴다() {
        IpoSubscriptionSizer.Deal spac = IpoSubscriptionSizer.Deal.of("S", "SPAC", null, null, new BigDecimal("2000"),
                MON, MON.plusDays(1)).orElseThrow();

        IpoSubscriptionSizer.Sizing sizing = sizer.size(spac, List.of(spac));

        assertEquals(2000, sizing.qty());
        assertEquals(new BigDecimal("2000000"), sizing.margin());
        assertEquals("추천 청약: 2,000주 · 증거금 약 2,000,000원(예산 200만 원 중) — 스팩 비례: 청약이 겹치는 추천 딜 없음, 예산 안 최대 수량"
                + "(스팩 비례 청약으로 10주를 배정받은 사례가 있어 기본을 비례로 둔다). 신고서 공모가 2,000원 기준 · "
                + "증거금률 50%·최소 10주·10주 단위 가정, 주관사 공고 확인.", sizer.text(sizing));
    }

    @Test
    void 확정가가_없으면_희망_밴드_상단으로_계산하고_확정가_전으로_표시한다() {
        IpoSubscriptionSizer.Deal banded = IpoSubscriptionSizer.Deal.of("A", "EQUAL_ONLY", null, new BigDecimal("15800"),
                new BigDecimal("13800"), MON, MON.plusDays(1)).orElseThrow();

        assertEquals(IpoSubscriptionSizer.PriceBasis.BAND_HIGH, banded.basis());
        assertTrue(sizer.text(sizer.size(banded, List.of())).contains("증거금 약 79,000원(예산 200만 원 중) — 균등 최소 수량. "
                + "확정가 전 — 희망 상단 15,800원 기준"));
    }

    @Test
    void 겹치는_딜_최소_증거금만으로_예산을_넘으면_최소_수량과_예산_초과를_알린다() {
        IpoSubscriptionSizer.Deal target = deal("A", "PROPORTIONAL", "300000", MON, MON);
        IpoSubscriptionSizer.Deal big = deal("B", "EQUAL_ONLY", "200000", MON, MON);

        IpoSubscriptionSizer.Sizing sizing = sizer.size(target, List.of(target, big));

        assertTrue(sizing.overBudget());
        assertEquals(10, sizing.qty());
        assertTrue(sizer.text(sizing).contains("예산 초과: 겹치는 딜 최소 증거금 1,000,000원"), sizer.text(sizing));
    }

    @Test
    void 패스_판단보류_공모가_모름은_금액을_추천하지_않고_일정을_모르면_겹침에서_빠진다() {
        assertTrue(IpoSubscriptionSizer.Deal.of("A", "PASS", new BigDecimal("10000"), null, null, MON, MON).isEmpty());
        assertTrue(IpoSubscriptionSizer.Deal.of("A", "PENDING", new BigDecimal("10000"), null, null, MON, MON).isEmpty());
        assertTrue(IpoSubscriptionSizer.Deal.of("A", null, new BigDecimal("10000"), null, null, MON, MON).isEmpty());
        assertTrue(IpoSubscriptionSizer.Deal.of("A", "EQUAL_ONLY", null, null, null, MON, MON).isEmpty());
        IpoSubscriptionSizer.Deal unscheduled = deal("B", "EQUAL_ONLY", "10000", null, null);
        assertFalse(deal("A", "PROPORTIONAL", "10000", MON, MON).overlaps(unscheduled));
    }

    @Test
    void 설정값을_바꾸면_그대로_쓰고_잘못된_값은_거절한다() {
        IpoSubscriptionSizer custom = new IpoSubscriptionSizer(new IpoSubscriptionProperties(new BigDecimal("1234500"),
                new BigDecimal("1.0"), 5, 5));
        IpoSubscriptionSizer.Deal target = deal("A", "PROPORTIONAL", "10000", MON, MON);

        IpoSubscriptionSizer.Sizing sizing = custom.size(target, List.of());

        assertEquals(120, sizing.qty());   // (1,234,500 − 50,000) ÷ (10,000 × 5) = 23단위 → 5 + 115
        assertEquals("1,234,500원", custom.budgetText());
        assertThrows(IllegalArgumentException.class,
                () -> new IpoSubscriptionProperties(new BigDecimal("2000000"), new BigDecimal("1.5"), 10, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new IpoSubscriptionProperties(BigDecimal.ZERO, new BigDecimal("0.5"), 10, 10));
    }
}

package com.autostock.ipo;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IpoAlertPlanner — 단계별 알림의 대상·기준값·문구(2026-10-13, aiDoc/ipo-automation.md).
 */
class IpoAlertPlannerTest {

    private static final Instant OLD = Instant.parse("2026-09-01T00:00:00Z");

    /** 브릴스 꼴 공모주 — 청약 10/20(화)~10/21(수), 환불 10/23(금). */
    private static IpoDealEntity brils() {
        IpoDealEntity deal = new IpoDealEntity("01801026", "브릴스", "20261001000476", "DART", OLD);
        deal.applyOfferingDetail(new DartClient.OfferingDetail("20261001000476", LocalDate.of(2026, 10, 20),
                LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 23), "한국투자증권", new BigDecimal("16500"), null), OLD);
        deal.classifyOffering(OfferingKind.IPO, OLD);
        return deal;
    }

    private static void recommend(IpoDealEntity deal) {
        deal.applyDemandForecast(new BigDecimal("1187.74"), new BigDecimal("0.2175"), "20261016000234", OLD);
        deal.applyRecommendation(IpoRecommendation.RECOMMEND, "둘 다 충족", OLD);
    }

    private static List<String> phases(IpoAlertPlanner.Plan plan) {
        return plan.alerts().stream().map(IpoAlertPlanner.PlannedAlert::phase).toList();
    }

    @Test
    void 수요예측_결과_알림에_확정가가_밴드의_어디인지_싣고_청약이_끝나면_보내지_않는다() {
        IpoDealEntity deal = brils();
        recommend(deal);
        deal.applyFinalPrice(new BigDecimal("19500"), new BigDecimal("16500"), new BigDecimal("19500"), OLD);

        IpoAlertPlanner.Plan plan = IpoAlertPlanner.plan(List.of(deal), LocalDate.of(2026, 10, 17), Set.of());

        assertEquals(List.of("METRICS"), phases(plan));
        assertEquals("[수요예측 결과] 브릴스 — 확정 공모가 19,500원(희망 16,500~19,500원 상단) · 기관경쟁률 1,187.74:1 · "
                + "의무보유확약 21.75% → 권고 RECOMMEND(청약 권고). 청약 10/20(화)~10/21(수), 한국투자증권.",
                plan.alerts().get(0).message());
        assertEquals("20261016000234", plan.alerts().get(0).key());
        assertTrue(IpoAlertPlanner.plan(List.of(deal), LocalDate.of(2026, 10, 22), Set.of()).alerts().isEmpty(),
                "청약이 끝난 뒤에는 수요예측 결과를 알리지 않는다");
    }

    @Test
    void 이미_보낸_단계는_다시_계획하지_않는다() {
        IpoDealEntity deal = brils();
        recommend(deal);

        IpoAlertPlanner.Plan plan = IpoAlertPlanner.plan(List.of(deal), LocalDate.of(2026, 10, 19),
                Set.of(IpoAlertLogEntity.key("01801026", "METRICS", "20261016000234")));

        assertEquals(List.of("D-1"), phases(plan));
        assertTrue(plan.alerts().get(0).message().contains("공모가 16,500원(신고서 모집가액·확정 전)"), plan.alerts().get(0).message());
    }

    @Test
    void 관심_딜은_청약_마감일_환불일_상장_전날에도_알린다() {
        IpoDealEntity deal = brils();
        recommend(deal);
        deal.applyFinalPrice(new BigDecimal("19500"), null, null, OLD);
        deal.applyMetrics(null, null, LocalDate.of(2026, 10, 29), OLD);   // 상장일을 사람이 넣었다
        Set<String> sent = Set.of(IpoAlertLogEntity.key("01801026", "METRICS", "20261016000234"));

        assertEquals(List.of("END"), phases(IpoAlertPlanner.plan(List.of(deal), LocalDate.of(2026, 10, 21), sent)));
        IpoAlertPlanner.Plan refund = IpoAlertPlanner.plan(List.of(deal), LocalDate.of(2026, 10, 23), sent);
        assertEquals(List.of("REFUND"), phases(refund));
        assertEquals("2026-10-23", refund.alerts().get(0).key());
        IpoAlertPlanner.Plan listingEve = IpoAlertPlanner.plan(List.of(deal), LocalDate.of(2026, 10, 28), sent);
        assertEquals(List.of("LISTING-D1"), phases(listingEve));
        assertEquals("[상장 D-1] 브릴스 — 내일 10/29(목) 상장. 공모가 19,500원(확정)", listingEve.alerts().get(0).message());
    }

    @Test
    void 유상증자는_어떤_알림도_없다() {
        IpoDealEntity rights = brils();
        rights.classifyOffering(OfferingKind.RIGHTS, OLD);

        IpoAlertPlanner.Plan plan = IpoAlertPlanner.plan(List.of(rights), LocalDate.of(2026, 10, 19), Set.of());

        assertTrue(plan.isEmpty());
    }

    @Test
    void 금액과_날짜는_천_단위_쉼표와_요일로_쓴다() {
        assertEquals("1,234,500원", IpoAlertPlanner.won(new BigDecimal("1234500.0000")));
        assertEquals("10/19(월)", IpoAlertPlanner.day(LocalDate.of(2026, 10, 19)));
    }
}

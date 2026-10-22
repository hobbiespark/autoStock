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

    /** 청약 추천 비례까지(점수 +3)로 매긴 딜 — 사유 글은 {@link IpoSubscriptionAdvisor}가 만드는 모양 그대로. */
    private static void recommend(IpoDealEntity deal) {
        deal.applyDemandForecast(new BigDecimal("1187.74"), new BigDecimal("0.2175"), "20261016000234", OLD);
        deal.applyRecommendation(IpoRecommendation.RECOMMEND, "비례까지(점수 +3) — 기관 1,187.74:1(+2) · 확약 21.75%(직전 20건 중앙값 "
                + "4.10% 이상, +1). 청약: 균등 최소 단위 + 비례 추가 (DART 수요예측 결과 20261016000234)", OLD);
        deal.applyAdvice("PROPORTIONAL", 3, OLD);
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
        assertEquals("[청약 추천] 브릴스 — 확정 공모가 19,500원(희망 16,500~19,500원 상단). 비례까지(점수 +3) — 기관 1,187.74:1(+2) · "
                + "확약 21.75%(직전 20건 중앙값 4.10% 이상, +1). 청약: 균등 최소 단위 + 비례 추가. 청약 10/20(화)~10/21(수), 한국투자증권.",
                plan.alerts().get(0).message());
        assertEquals("20261016000234", plan.alerts().get(0).key());
        assertTrue(IpoAlertPlanner.plan(List.of(deal), LocalDate.of(2026, 10, 22), Set.of()).alerts().isEmpty(),
                "청약이 끝난 뒤에는 수요예측 결과를 알리지 않는다");
    }

    @Test
    void 청약_일정을_모르면_확정_신고서가_7일_넘게_지났거나_상장했으면_수요예측_결과를_알리지_않는다() {
        // 10/15 07:53 실측: 주요정보가 없어 일정이 빈 멜콘(확정 신고 9/29, 오늘 상장)에 "[수요예측 결과]"가 나갔다
        IpoDealEntity melcon = new IpoDealEntity("01234567", "멜콘", "20260929000191", "DART", OLD);
        melcon.classifyOffering(OfferingKind.IPO, OLD);
        melcon.applyDemandForecast(new BigDecimal("1136.84"), new BigDecimal("0.3607"), "20260929000191", OLD);
        melcon.applyRecommendation(IpoRecommendation.RECOMMEND, "둘 다 충족", OLD);

        assertEquals(List.of("METRICS"), phases(IpoAlertPlanner.plan(List.of(melcon), LocalDate.of(2026, 10, 2), Set.of())),
                "확정 신고 사흘 뒤 — 아직 청약 전일 수 있다");
        assertTrue(IpoAlertPlanner.plan(List.of(melcon), LocalDate.of(2026, 10, 15), Set.of()).alerts().isEmpty(),
                "확정 신고 16일 뒤 — 청약은 끝났다");

        melcon.applyMetrics(null, null, LocalDate.of(2026, 10, 3), OLD);   // 상장일을 안다(가정) — 그날부터는 끝난 것
        assertTrue(IpoAlertPlanner.plan(List.of(melcon), LocalDate.of(2026, 10, 3), Set.of()).alerts().stream()
                .noneMatch(alert -> "METRICS".equals(alert.phase())));
    }

    @Test
    void 청약이_끝났는지는_마감일_상장일_확정_신고일_순으로_본다() {
        LocalDate today = LocalDate.of(2026, 10, 15);
        assertTrue(IpoAlertPlanner.subscriptionOpen(LocalDate.of(2026, 10, 15), null, "20260901000001", today), "마감일이 우선");
        assertTrue(!IpoAlertPlanner.subscriptionOpen(LocalDate.of(2026, 10, 14), null, null, today));
        assertTrue(!IpoAlertPlanner.subscriptionOpen(LocalDate.of(2026, 10, 20), today, null, today), "상장일이 오늘이면 끝");
        assertTrue(IpoAlertPlanner.subscriptionOpen(null, null, "20261008000300", today), "확정 신고 7일 전 — 열림");
        assertTrue(!IpoAlertPlanner.subscriptionOpen(null, null, "20261007000327", today), "확정 신고 8일 전 — 끝");
        assertTrue(IpoAlertPlanner.subscriptionOpen(null, null, null, today), "확정 신고도 없으면 수요예측 전");
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
    void 청약_전날과_시작일_알림은_청약_권고_딜만() {
        // 2026-10-22: 10/22 07:05 패스 딜(이에스티 −2·래블업 −5)에도 [청약 시작]이 나갔다 — 사용자 요청으로 권고 딜만
        IpoDealEntity pass = brils();
        pass.applyRecommendation(IpoRecommendation.SKIP, "패스(점수 -2) — 기관 630.10:1(+1)", OLD);
        IpoDealEntity recommended = brils();
        recommend(recommended);
        Set<String> sent = Set.of(IpoAlertLogEntity.key("01801026", "METRICS", "20261016000234"));

        assertTrue(IpoAlertPlanner.plan(List.of(pass), LocalDate.of(2026, 10, 19), Set.of()).alerts().isEmpty(), "패스 — D-1 없음");
        assertTrue(IpoAlertPlanner.plan(List.of(pass), LocalDate.of(2026, 10, 20), Set.of()).alerts().isEmpty(), "패스 — 시작일 없음");
        assertEquals(List.of("START"), phases(IpoAlertPlanner.plan(List.of(recommended), LocalDate.of(2026, 10, 20), sent)));
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

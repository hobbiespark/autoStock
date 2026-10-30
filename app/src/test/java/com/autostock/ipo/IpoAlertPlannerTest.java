package com.autostock.ipo;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
                + "확약 21.75%(직전 20건 중앙값 4.10% 이상, +1). 청약: 균등 최소 단위 + 비례 추가. 청약 10/20(화)~10/21(수), 한국투자증권. "
                + "추천 청약: 200주 · 증거금 약 1,950,000원(예산 200만 원 중) — 비례: 청약이 겹치는 추천 딜 없음, 예산 안 최대 수량. "
                + "확정가 19,500원 기준 · 증거금률 50%·최소 10주·10주 단위 가정, 주관사 공고 확인.",
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

    // ── 2026-10-30: 스팩 청약 알림, 마감일·상장일 아침 알림, 시초가, 주간 요약, 청약 금액 추천 ─────────────────────

    private static final IpoSubscriptionSizer SIZER = new IpoSubscriptionSizer(IpoSubscriptionProperties.DEFAULT);

    /** 스팩 — 청약 10/20(화)~10/21(수), 공모가 2,000원(신고서). 등급은 스케줄러가 매기는 모양 그대로(SPAC, 권고 칸 RECOMMEND). */
    private static IpoDealEntity spac() {
        IpoDealEntity deal = new IpoDealEntity("01999999", "에스케이증권제14호기업인수목적", "20261014000196", "DART", OLD);
        deal.applyOfferingDetail(new DartClient.OfferingDetail("20261014000196", LocalDate.of(2026, 10, 20),
                LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 23), "에스케이증권", new BigDecimal("2000"), null), OLD);
        deal.classifyOffering(OfferingKind.IPO, OLD);
        deal.applyRecommendation(IpoSubscriptionAdvisor.Grade.SPAC.recommendation(), IpoSyncScheduler.SPAC_REASON, OLD);
        deal.applyAdvice("SPAC", null, OLD);
        return deal;
    }

    private static IpoAlertPlanner.Plan planAt(List<IpoDealEntity> deals, LocalDate today, LocalTime now, Set<String> sent) {
        return IpoAlertPlanner.plan(deals, today, now, sent, SIZER);
    }

    @Test
    void 스팩도_청약_전날과_시작일_알림을_받고_겹치는_추천_딜과_예산을_나눈다() {
        IpoDealEntity spac = spac();
        IpoDealEntity brils = brils();
        recommend(brils);
        brils.applyFinalPrice(new BigDecimal("19500"), null, null, OLD);
        Set<String> sent = Set.of(IpoAlertLogEntity.key("01801026", "METRICS", "20261016000234"));

        IpoAlertPlanner.Plan eve = planAt(List.of(spac, brils), LocalDate.of(2026, 10, 19), LocalTime.of(8, 20), sent);

        assertEquals(List.of("D-1", "D-1"), phases(eve));
        String spacMessage = eve.alerts().get(0).message();
        assertTrue(spacMessage.startsWith("[청약 D-1] 에스케이증권제14호기업인수목적 — 내일 10/20(화)부터 청약, 공모가 2,000원(신고서 모집가액·확정 전)"),
                spacMessage);
        assertTrue(spacMessage.contains("추천: 스팩 — 기업인수목적회사: 점수 없이 청약 일정 알림"), spacMessage);
        // 브릴스 최소 97,500원·스팩 최소 10,000원을 뺀 1,892,500원을 비례 두 곳이 나눔 → 스팩 946,250원 ÷ 1만 원 = 94단위
        assertTrue(spacMessage.contains("추천 청약: 950주 · 증거금 약 950,000원(예산 200만 원 중) — 스팩 비례: 청약이 겹치는 딜 1곳 "
                + "최소 증거금 97,500원을 뺀 나머지, 비례 딜 2곳이 나눔"), spacMessage);
        assertTrue(eve.alerts().get(1).message().contains("추천 청약: 100주 · 증거금 약 975,000원(예산 200만 원 중)"),
                eve.alerts().get(1).message());
        assertEquals(List.of("START"), phases(planAt(List.of(spac), LocalDate.of(2026, 10, 20), LocalTime.of(8, 20), Set.of())));
    }

    @Test
    void 청약_마감일_08시30분에_추천과_스팩_딜에_오늘_마감을_한_번_더_알린다() {
        IpoDealEntity brils = brils();
        recommend(brils);
        brils.applyFinalPrice(new BigDecimal("19500"), null, null, OLD);
        IpoDealEntity pass = new IpoDealEntity("01700000", "패스회사", "20261001000999", "DART", OLD);
        pass.applyOfferingDetail(new DartClient.OfferingDetail("20261001000999", LocalDate.of(2026, 10, 20),
                LocalDate.of(2026, 10, 21), null, "대신증권", new BigDecimal("10000"), null), OLD);
        pass.applyRecommendation(IpoRecommendation.SKIP, "패스(점수 -2)", OLD);
        pass.applyAdvice("PASS", -2, OLD);
        LocalDate end = LocalDate.of(2026, 10, 21);
        Set<String> sent = Set.of(IpoAlertLogEntity.key("01801026", "METRICS", "20261016000234"));

        assertEquals(List.of("END"), phases(planAt(List.of(brils, pass), end, LocalTime.of(7, 5), sent)), "08:30 전에는 마감 재알림 없음");
        IpoAlertPlanner.Plan morning = planAt(List.of(brils, pass), end, LocalTime.of(8, 30), sent);

        assertEquals(List.of("END-AM", "END"), phases(morning));
        assertEquals("[오늘 청약 마감] 브릴스 — 오늘 10/21(수) 청약 마지막 날(마감 시각은 증권사마다 다르다), 주관사 한국투자증권, "
                + "공모가 19,500원(확정). 추천: 비례까지(점수 +3) — 기관 1,187.74:1(+2) · 확약 21.75%(직전 20건 중앙값 4.10% 이상, +1). "
                + "청약: 균등 최소 단위 + 비례 추가. 추천 청약: 200주 · 증거금 약 1,950,000원(예산 200만 원 중) — 비례: 청약이 겹치는 추천 딜 "
                + "없음, 예산 안 최대 수량. 확정가 19,500원 기준 · 증거금률 50%·최소 10주·10주 단위 가정, 주관사 공고 확인. "
                + "청약 실행은 영웅문S#에서 수동.", morning.alerts().get(0).message());
        assertEquals("2026-10-21", morning.alerts().get(0).key());
        assertTrue(morning.alerts().get(1).message().contains("추천 청약: 200주"), "청약 마감일 알림에도 금액 추천");
        assertTrue(planAt(List.of(brils), end, LocalTime.of(9, 5),
                Set.of(IpoAlertLogEntity.key("01801026", "METRICS", "20261016000234"),
                        IpoAlertLogEntity.key("01801026", "END", "2026-10-21"),
                        IpoAlertLogEntity.key("01801026", "END-AM", "2026-10-21"))).alerts().isEmpty(), "한 번만");
    }

    @Test
    void 상장일_08시30분에_공모가와_참고_매도_기준을_알린다() {
        IpoDealEntity deal = new IpoDealEntity("01888888", "인텔리빅스", "20261001000777", "DART", OLD);
        deal.classifyOffering(OfferingKind.IPO, OLD);
        deal.applyFinalPrice(new BigDecimal("9000"), null, null, OLD);
        deal.applyListing("123456", LocalDate.of(2026, 10, 28), OLD);
        deal.applyRecord(10, null, 1, null, null, null, OLD);   // 배정 1주 — 관심 딜
        LocalDate listing = LocalDate.of(2026, 10, 28);

        IpoAlertPlanner.Plan early = planAt(List.of(deal), listing, LocalTime.of(8, 5), Set.of());
        IpoAlertPlanner.Plan morning = planAt(List.of(deal), listing, LocalTime.of(8, 30),
                Set.of(IpoAlertLogEntity.key("01888888", "LISTING", "2026-10-28")));

        assertEquals(List.of("LISTING"), phases(early));
        assertEquals(List.of("LISTING-AM"), phases(morning));
        assertEquals("[상장일 매도 안내] 인텔리빅스(123456) — 오늘 상장. 공모가 9,000원(확정), 배정 1주(기록 기준). 참고 매도 기준: 시초가 "
                + "매도(기본). 공모가 대비 +30% 11,700원 · +60% 14,400원 · +100% 18,000원 · +300% 36,000원(상장일 가격 범위는 공모가의 "
                + "60~400%). 09:05에 시초가와 공모가 대비 수익률을 다시 알린다. 매도는 영웅문S#에서 수동.", morning.alerts().get(0).message());
    }

    @Test
    void 상장일_시초가를_받으면_관심_딜에_공모가_대비_수익률을_알린다() {
        // 사용자 실적(10월): 인텔리빅스 공모가 9,000원, 1주 배정 → 19,420원 매도
        IpoDealEntity deal = new IpoDealEntity("01888888", "인텔리빅스", "20261001000777", "DART", OLD);
        deal.classifyOffering(OfferingKind.IPO, OLD);
        deal.applyFinalPrice(new BigDecimal("9000"), null, null, OLD);
        deal.applyListing("123456", LocalDate.of(2026, 10, 28), OLD);
        deal.applyRecord(10, null, 1, null, null, null, OLD);
        IpoDealEntity notInterested = new IpoDealEntity("01777777", "관심없음", "20261001000778", "DART", OLD);
        notInterested.classifyOffering(OfferingKind.IPO, OLD);
        notInterested.applyListing("654321", LocalDate.of(2026, 10, 28), OLD);
        LocalDate listing = LocalDate.of(2026, 10, 28);
        java.util.Map<String, BigDecimal> opens = java.util.Map.of("123456", new BigDecimal("19420"), "654321", new BigDecimal("5000"));

        List<IpoAlertPlanner.PlannedAlert> alerts = IpoAlertPlanner.openingAlerts(List.of(deal, notInterested), listing, Set.of(), opens);

        assertEquals(1, alerts.size());
        assertEquals("LISTING-OPEN", alerts.get(0).phase());
        assertEquals("[상장 시초가] 인텔리빅스(123456) — 시초가 19,420원, 공모가 9,000원 대비 +115.8%, 배정 1주 기준 차익 약 10,420원"
                + "(수수료·세금 전). 기본 기준: 시초가 매도. 매도는 영웅문S#에서 수동.", alerts.get(0).message());
        assertTrue(IpoAlertPlanner.openingAlerts(List.of(deal), listing,
                Set.of(IpoAlertLogEntity.key("01888888", "LISTING-OPEN", "2026-10-28")), opens).isEmpty(), "한 번만");
        assertTrue(IpoAlertPlanner.openingAlerts(List.of(deal), listing.plusDays(1), Set.of(), opens).isEmpty(), "상장일에만");
        assertEquals("-3.0%", IpoAlertPlanner.returnText(new BigDecimal("1940"), new BigDecimal("2000")));
    }

    @Test
    void 월요일_08시에_이번_주_청약과_상장_일정을_한_통으로_요약한다() {
        LocalDate monday = LocalDate.of(2026, 10, 19);
        IpoDealEntity brils = brils();
        recommend(brils);
        IpoDealEntity jincostech = new IpoDealEntity("01158632", "진코스텍", "20260910000579", "DART", OLD);
        jincostech.classifyOffering(OfferingKind.IPO, OLD);
        jincostech.applyFinalPrice(new BigDecimal("23500"), null, null, OLD);
        jincostech.applyListing("250030", LocalDate.of(2026, 10, 22), OLD);
        jincostech.applyRecommendation(IpoRecommendation.RECOMMEND, "균등만(점수 +2)", OLD);
        jincostech.applyAdvice("EQUAL_ONLY", 2, OLD);
        IpoDealEntity nextWeek = new IpoDealEntity("01600000", "다음주회사", "20261001000555", "DART", OLD);
        nextWeek.applyOfferingDetail(new DartClient.OfferingDetail("20261001000555", LocalDate.of(2026, 10, 26),
                LocalDate.of(2026, 10, 27), null, "대신증권", new BigDecimal("10000"), null), OLD);

        assertTrue(IpoAlertPlanner.weekly(List.of(brils), monday, LocalTime.of(7, 5), Set.of(), SIZER).isEmpty(), "08시 전");
        assertTrue(IpoAlertPlanner.weekly(List.of(brils), monday.plusDays(1), LocalTime.of(8, 0), Set.of(), SIZER).isEmpty(), "월요일만");
        IpoAlertPlanner.PlannedAlert weekly = IpoAlertPlanner.weekly(List.of(jincostech, brils, nextWeek), monday, LocalTime.of(8, 0),
                Set.of(), SIZER).orElseThrow();

        assertEquals("WEEKLY|WEEKLY|2026-10-19", IpoAlertLogEntity.key(weekly.corpCode(), weekly.phase(), weekly.key()));
        String[] lines = weekly.message().split("\n");
        assertEquals("[이번 주 공모주] 10/19(월)~10/25(일) — 청약 1건 · 상장 1건", lines[0]);
        assertTrue(lines[1].startsWith("· 청약 10/20(화)~10/21(수) 브릴스(한국투자증권) — 비례까지(점수 +3), 공모가 16,500원(신고서 모집가액·확정 전). "
                + "추천 청약: 240주 · 증거금 약 1,980,000원(예산 200만 원 중)"), lines[1]);
        assertTrue(lines[1].contains("확정가 전 — 신고서 모집가액 16,500원 기준"), lines[1]);
        assertEquals("· 상장 10/22(목) 진코스텍(250030) — 균등만(점수 +2), 공모가 23,500원(확정).", lines[2]);
        assertEquals("청약 실행·매도는 영웅문S#에서 수동.", lines[3]);
        assertEquals(4, lines.length, "다음 주 청약은 빠진다");
        assertTrue(IpoAlertPlanner.weekly(List.of(brils), monday, LocalTime.of(9, 5),
                Set.of(IpoAlertLogEntity.key("WEEKLY", "WEEKLY", "2026-10-19")), SIZER).isEmpty(), "주마다 한 번");
        assertEquals("[이번 주 공모주] 10/19(월)~10/25(일) — 청약·상장 일정 없음(공시·상장 확인 기준).",
                IpoAlertPlanner.weekly(List.of(), monday, LocalTime.of(8, 0), Set.of(), SIZER).orElseThrow().message());
    }
}

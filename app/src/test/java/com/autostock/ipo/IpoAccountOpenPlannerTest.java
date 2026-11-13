package com.autostock.ipo;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 계좌 개설 요청(2026-11-11 사용자 "보유하지 않은 계좌 증권사는 1개월에 1개씩 개설 요청"). */
class IpoAccountOpenPlannerTest {

    private static final Instant OLD = Instant.parse("2026-09-01T00:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 11);
    private static final Set<String> HELD = BrokerNames.keys(List.of("KB증권", "SK증권", "대신증권", "미래에셋증권", "삼성증권",
            "신한투자증권", "키움증권", "하나증권", "한국투자증권", "한화투자증권", "카카오페이증권"));

    private static IpoDealEntity deal(String corp, String name, LocalDate start, String grade, String... underwriters) {
        IpoDealEntity deal = new IpoDealEntity(corp, name, corp + "0001", "DART", OLD);
        deal.applyOfferingDetail(new DartClient.OfferingDetail(corp + "0001", start, start.plusDays(1), start.plusDays(3),
                underwriters[0], new BigDecimal("10000"), null, List.of(underwriters)), OLD);
        deal.classifyOffering(OfferingKind.IPO, OLD);
        if (grade != null) {
            IpoSubscriptionAdvisor.Grade g = IpoSubscriptionAdvisor.Grade.valueOf(grade);
            deal.applyRecommendation(g.recommendation(), g.label, OLD);
            deal.applyAdvice(grade, 1, OLD);
        }
        return deal;
    }

    @Test
    void 보유_계좌로_못_하는_다가오는_추천_딜이_많은_증권사를_고른다() {
        List<IpoDealEntity> deals = List.of(
                deal("A", "가나전자", LocalDate.of(2026, 11, 20), "EQUAL_ONLY", "엔에이치투자증권"),
                deal("B", "다라바이오", LocalDate.of(2026, 11, 25), "PROPORTIONAL", "엔에이치투자증권", "유안타증권"),
                deal("C", "마바로봇", LocalDate.of(2026, 11, 18), "EQUAL_ONLY", "유안타증권"),
                deal("D", "사아소재", LocalDate.of(2026, 11, 19), "EQUAL_ONLY", "신영증권", "에스케이증권"),   // SK로 청약 가능
                deal("E", "자차화학", LocalDate.of(2026, 3, 2), null, "엔에이치투자증권"),
                deal("F", "카타식품", LocalDate.of(2026, 11, 24), "PASS", "교보증권"));                     // 패스는 세지 않음

        IpoAlertPlanner.PlannedAlert alert = IpoAccountOpenPlanner.plan(deals, HELD, TODAY, null).orElseThrow();

        assertEquals("NH투자증권", alert.corpName(), "다가오는 추천은 NH·유안타 2곳씩 → 최근 1년 공모 수(NH 1 > 유안타 0)");
        assertEquals("ACCOUNT", alert.corpCode());
        assertEquals("ACCOUNT-OPEN", alert.phase());
        assertEquals("2026-11-11", alert.key());
        assertEquals("[계좌 개설 요청] NH투자증권 — 이번 달 개설할 증권사 1곳(신규 계좌는 한 달에 하나씩). 보유 계좌로 청약할 수 없는 "
                + "다가오는 추천 딜 2곳: 가나전자(11/20~11/21, 균등만), 다라바이오(11/25~11/26, 비례까지). 보유 계좌로 못 한 공모 인수: "
                + "최근 1년 1건 · 3년 1건 · 5년 1건(지난 공모 이력은 아직 채우는 중). 개설하면 application.yml "
                + "ipo.subscription.held-brokers에 더하고 재기동 — 이 증권사 딜은 청약 점수 +1. 다음 요청은 12/11 이후.", alert.message());
    }

    private static IpoUnderwritingHistoryEntity past(String corp, String name, LocalDate start, String... underwriters) {
        return new IpoUnderwritingHistoryEntity(corp, name, corp + "0009", start.minusDays(20), start, List.of(underwriters), OLD);
    }

    @Test
    void 일년_실적이_같으면_3년_그다음_5년_인수_실적으로_고르고_이력은_딜과_회사가_겹치면_딜을_쓴다() {
        // 다가오는 추천 딜 없음. 1년: 교보 1·신영 1 같음 → 3년: 신영 3 > 교보 1 → 신영
        List<IpoDealEntity> deals = List.of(deal("A", "가나전자", LocalDate.of(2026, 6, 2), null, "교보증권"));
        List<IpoUnderwritingHistoryEntity> history = List.of(
                past("A", "가나전자(이력)", LocalDate.of(2026, 6, 2), "신영증권"),          // 딜과 겹침 → 딜(교보)을 씀
                past("B", "다라", LocalDate.of(2026, 3, 3), "신영증권"),
                past("C", "마바", LocalDate.of(2024, 5, 7), "신영증권"),
                past("D", "사아", LocalDate.of(2024, 9, 9), "신영증권"),
                past("E", "자차", LocalDate.of(2022, 1, 5), "교보증권", "유안타증권"),
                past("F", "카타", LocalDate.of(2020, 1, 5), "신영증권"),                   // 5년 밖
                past("G", "파하", LocalDate.of(2025, 2, 2), "신영증권", "케이비증권"));      // 보유(KB)와 함께 → 세지 않음

        IpoAlertPlanner.PlannedAlert alert = IpoAccountOpenPlanner.plan(deals, history, LocalDate.of(2021, 11, 11), HELD,
                TODAY, null).orElseThrow();

        assertEquals("신영증권", alert.corpName());
        assertTrue(alert.message().contains("보유 계좌로 못 한 공모 인수: 최근 1년 1건 · 3년 3건 · 5년 3건. "), alert.message());

        // 1년·3년이 같으면 5년: 교보(딜 A + 이력 E) 1·1·2 vs 유안타(이력 H) 1·1·1 → 교보
        IpoAlertPlanner.PlannedAlert byFive = IpoAccountOpenPlanner.plan(deals, List.of(
                past("E", "자차", LocalDate.of(2022, 1, 5), "교보증권"),
                past("H", "하나", LocalDate.of(2025, 12, 1), "유안타증권")), LocalDate.of(2021, 11, 11), HELD, TODAY, null)
                .orElseThrow();
        assertEquals("교보증권", byFive.corpName());
        assertTrue(byFive.message().contains("최근 1년 1건 · 3년 1건 · 5년 2건"), byFive.message());
    }

    @Test
    void 이력을_5년_다_채우지_못했으면_문구에_채운_범위를_밝힌다() {
        assertEquals("(지난 공모 이력은 아직 채우는 중)", IpoAccountOpenPlanner.coverageNote(TODAY, null));
        assertEquals("(지난 공모 이력은 2025-03-01부터 채움, 5년 채우는 중)",
                IpoAccountOpenPlanner.coverageNote(TODAY, LocalDate.of(2025, 3, 1)));
        assertEquals("", IpoAccountOpenPlanner.coverageNote(TODAY, LocalDate.of(2021, 11, 11)));
    }

    @Test
    void 한_달이_지나야_다음_요청을_하고_모두_보유_계좌로_되면_요청하지_않는다() {
        List<IpoDealEntity> deals = List.of(deal("A", "가나전자", LocalDate.of(2026, 11, 20), "EQUAL_ONLY", "엔에이치투자증권"));

        assertEquals(Optional.empty(), IpoAccountOpenPlanner.plan(deals, HELD, TODAY, LocalDate.of(2026, 10, 12)),
                "10/12 요청 → 11/12부터");
        assertTrue(IpoAccountOpenPlanner.plan(deals, HELD, TODAY, LocalDate.of(2026, 10, 11)).isPresent());

        List<IpoDealEntity> covered = List.of(deal("A", "가나전자", LocalDate.of(2026, 11, 20), "EQUAL_ONLY", "케이비증권"));
        assertEquals(Optional.empty(), IpoAccountOpenPlanner.plan(covered, HELD, TODAY, null));
        assertEquals(Optional.empty(), IpoAccountOpenPlanner.plan(deals, Set.of(), TODAY, null), "보유 목록이 비면 끈다");
    }
}

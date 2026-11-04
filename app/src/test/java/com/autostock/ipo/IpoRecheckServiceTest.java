package com.autostock.ipo;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 청약일 재판단 /ipo(2026-11-05) — 명령 해석, 균등·비례 예상 배정, 추천 청약 유지/균등 최소만 권장, 딜 찾기.
 * 브릴스 꼴 딜: 청약 10/20~10/21, 공모가 16,500원, 비례까지 → 예산 200만 원 추천 240주(증거금 1,980,000원).
 */
class IpoRecheckServiceTest {

    private static final Instant OLD = Instant.parse("2026-09-01T00:00:00Z");
    /** 2026-10-20(화) 09:00 KST — 청약 1일차. */
    private static final Clock DAY1 = Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC);

    private static IpoDealEntity brils() {
        IpoDealEntity deal = new IpoDealEntity("01801026", "브릴스", "20261001000476", "DART", OLD);
        deal.applyOfferingDetail(new DartClient.OfferingDetail("20261001000476", LocalDate.of(2026, 10, 20),
                LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 23), "한국투자증권", new BigDecimal("16500"), null), OLD);
        deal.classifyOffering(OfferingKind.IPO, OLD);
        deal.applyDemandForecast(new BigDecimal("1187.74"), new BigDecimal("0.2175"), "20261016000234", OLD);
        deal.applyRecommendation(IpoRecommendation.RECOMMEND, "비례까지(점수 +3)", OLD);
        deal.applyAdvice("PROPORTIONAL", 3, OLD);
        return deal;
    }

    private static IpoDealEntity pass() {
        IpoDealEntity deal = new IpoDealEntity("01700000", "패스회사", "20261001000999", "DART", OLD);
        deal.applyOfferingDetail(new DartClient.OfferingDetail("20261001000999", LocalDate.of(2026, 10, 20),
                LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 23), "NH투자증권", new BigDecimal("10000"), null), OLD);
        deal.classifyOffering(OfferingKind.IPO, OLD);
        deal.applyRecommendation(IpoRecommendation.SKIP, "패스(점수 -2)", OLD);
        deal.applyAdvice("PASS", -2, OLD);
        return deal;
    }

    private static IpoRecheckService service(Clock clock, IpoDealEntity... deals) {
        IpoDealRepository repository = mock(IpoDealRepository.class);
        when(repository.findBySubscriptionStartGreaterThanEqualOrSubscriptionStartIsNull(any())).thenReturn(List.of(deals));
        return new IpoRecheckService(repository, IpoSubscriptionProperties.DEFAULT, clock);
    }

    @Test
    void 명령을_회사와_숫자로_나눈다() {
        IpoRecheck.Args a = IpoRecheck.parse("/ipo 브릴스 1,500:1 120,000 600000").orElseThrow();
        assertEquals("브릴스", a.query());
        assertEquals(0, new BigDecimal("1500").compareTo(a.rate()));
        assertEquals(120_000L, a.applicants());
        assertEquals(600_000L, a.generalShares());
        IpoRecheck.Args b = IpoRecheck.parse("  /ipo 한화 플러스 3.5 ").orElseThrow();
        assertEquals("한화 플러스", b.query());
        assertEquals(null, b.applicants());
        assertTrue(IpoRecheck.parse("/ipo").isEmpty());
        assertTrue(IpoRecheck.parse("/ipo 1500").isEmpty(), "회사 이름이 없다");
        assertTrue(IpoRecheck.parse("/ipo 브릴스").isEmpty(), "경쟁률이 없다");
        assertTrue(IpoRecheck.parse("/iposomething 1").isEmpty());
        assertTrue(IpoRecheck.parse("/ipo 브릴스 0").isEmpty());
    }

    @Test
    void 균등_예상은_일반물량의_절반을_청약건수로_나눈다() {
        assertEquals(0, new BigDecimal("2.5").compareTo(IpoRecheck.equalPerAccount(600_000L, 120_000L).orElseThrow()));
        assertTrue(IpoRecheck.equalPerAccount(null, 120_000L).isEmpty());
        assertTrue(IpoRecheck.equalPerAccount(600_000L, null).isEmpty());
    }

    @Test
    void 비례_추가분_기대_배정이_1주_미만이면_균등_최소만_권장한다() {
        String reply = service(DAY1, brils()).reply("/ipo 브릴스 1500 120000 600000");
        assertTrue(reply.startsWith("[청약 재판단] 브릴스 — 청약 1일차(10/20(화), 마감 10/21(수)) 기준: 경쟁률 1,500:1"), reply);
        assertTrue(reply.contains("등급: 비례까지(그대로"), reply);
        assertTrue(reply.contains("균등 예상 배정: 계좌당 약 2.5주(균등 300,000주 ÷ 120,000건)"), reply);
        assertTrue(reply.contains("추천 240주 청약 시 약 0.08주(비례 경쟁률 약 3,000:1 가정)"), reply);
        assertTrue(reply.contains("재판단: 균등 최소 10주(증거금 약 82,500원)만 권장"), reply);
        assertTrue(reply.contains("원래 추천 240주 · 증거금 약 1,980,000원"), reply);
        assertTrue(reply.contains("1일차 경쟁률은 마감일에 보통 더 오른다"), reply);
    }

    @Test
    void 경쟁률이_낮으면_추천_청약을_유지한다() {
        String reply = service(DAY1, brils()).reply("/ipo 브릴스 50");
        assertTrue(reply.contains("재판단: 추천 청약 유지 — 원래 추천 240주"), reply);
        assertTrue(reply.contains("약 2.3주 기대"), reply);
        assertTrue(reply.contains("균등 예상 배정: 청약건수·일반물량을 붙이면 계산"), reply);
    }

    @Test
    void 마감일에는_1일차_안내를_붙이지_않는다() {
        Clock day2 = Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC);
        String reply = service(day2, brils()).reply("/ipo 브릴스 3000");
        assertTrue(reply.contains("청약 마감일(10/21(수)) 기준"), reply);
        assertFalse(reply.contains("1일차 경쟁률"), reply);
    }

    @Test
    void 추천_대상이_아닌_딜은_최소_수량만_계산한다() {
        String reply = service(DAY1, brils(), pass()).reply("/ipo 패스회사 800");
        assertTrue(reply.contains("등급: 패스"), reply);
        assertTrue(reply.contains("최소 10주 청약 시 약 0.01주"), reply);
        assertTrue(reply.contains("청약 금액 추천 대상 아님(패스)"), reply);
    }

    @Test
    void 못_찾거나_명령이_불완전하면_사용법과_추천_딜을_보낸다() {
        String notFound = service(DAY1, brils()).reply("/ipo 없는회사 100");
        assertTrue(notFound.contains("찾지 못함"), notFound);
        String help = service(DAY1, brils()).reply("/ipo");
        assertTrue(help.startsWith("사용법: /ipo"), help);
        assertTrue(help.contains("추천 딜: 브릴스 10/20(화)~10/21(수) 비례까지"), help);
    }

    @Test
    void 이름은_띄어쓰기와_기업인수목적_표기를_무시하고_찾는다() {
        assertEquals(IpoRecheck.normalize("에스케이증권제14호기업인수목적"), IpoRecheck.normalize("에스케이증권 제14호 스팩"));
    }
}

package com.autostock.ipo;

import com.autostock.monitor.AppCommandCatalog;
import com.autostock.monitor.AppCommandRunner;
import com.autostock.monitor.AppCommandRunnerIpoAccess;
import com.autostock.support.PostgresDataJpaTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 공모주 할 일 셋(VALUNIVO 900) — 실제 PostgreSQL. 값은 합성이다. 시계는 2026-11-18(수) 10:00 KST.
 * <ul>
 *   <li>가나테크: 신고서 두 행(원본·정정), 청약 11/19~11/20, 상장 11/27, 확정가 20,000원, 추천</li>
 *   <li>가나바이오: 청약 11/24~11/25, 모집가액 15,000원, 비추천("가나"로 부르면 두 곳이 맞는다)</li>
 *   <li>다라전자: 청약 11/3~11/4, 11/12 상장, 확정가 10,000원, 추천(최근 상장)</li>
 * </ul>
 * 마지막 시험은 {@link AppCommandRunner}와 명령 원천을 거친 조립 경로(앱 봇 실행, 화면 경로 거절)다.
 */
class IpoCommandServiceDbTest extends PostgresDataJpaTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T01:00:00Z"), ZoneOffset.UTC);
    private static final Instant OLD = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    IpoDealRepository deals;

    @BeforeEach
    void seed() {
        deals.save(deal("00000001", "가나테크", "20261020000001", LocalDate.of(2026, 11, 19), LocalDate.of(2026, 11, 20),
                "20000", IpoRecommendation.RECOMMEND, LocalDate.of(2026, 11, 27), "900001"));
        deals.save(new IpoDealEntity("00000001", "가나테크", "20261010000001", "DART", OLD));   // 상세 없는 원본 행
        deals.save(deal("00000002", "가나바이오", "20261021000002", LocalDate.of(2026, 11, 24), LocalDate.of(2026, 11, 25),
                null, IpoRecommendation.SKIP, null, null));
        deals.save(deal("00000003", "다라전자", "20261001000003", LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 4),
                "10000", IpoRecommendation.RECOMMEND, LocalDate.of(2026, 11, 12), "900003"));
    }

    private static IpoDealEntity deal(String code, String name, String rcept, LocalDate start, LocalDate end, String finalPrice,
                                      IpoRecommendation recommendation, LocalDate listing, String stockCode) {
        IpoDealEntity deal = new IpoDealEntity(code, name, rcept, "DART", OLD);
        deal.applyOfferingDetail(new DartClient.OfferingDetail(rcept, start, end, end.plusDays(2), "합성증권", new BigDecimal("15000"), null), OLD);
        deal.classifyOffering(OfferingKind.IPO, OLD);
        if (finalPrice != null) {
            deal.applyFinalPrice(new BigDecimal(finalPrice), new BigDecimal("15000"), new BigDecimal("20000"), OLD);
        }
        deal.applyRecommendation(recommendation, recommendation.name(), OLD);
        if (listing != null) {
            deal.applyListing(stockCode, listing, OLD);
        }
        return deal;
    }

    private IpoCommandService service() {
        return new IpoCommandService(deals, new IpoDealCommandService(deals, null, CLOCK), IpoSubscriptionProperties.DEFAULT, CLOCK);
    }

    private IpoCommandService.Reply run(String id, String text) {
        return service().run(id, text).orElseThrow();
    }

    private List<IpoDealEntity> rows(String code) {
        return deals.findByCorpCode(code);
    }

    @Test
    void 일정_보기는_앞으로_14일의_청약_상장과_최근_상장_기록을_날짜순으로_답한다() {
        IpoCommandService.Reply reply = run("ipo_view", "");

        String text = reply.text();
        assertTrue(reply.ok(), text);
        assertTrue(text.startsWith("[공모주 일정] 11/18(수)~12/1(화) — 청약 2건 · 상장 1건"), text);
        assertTrue(text.contains("· 청약 11/19(목)~11/20(금) 가나테크(합성증권)"), text);
        assertTrue(text.contains("· 청약 11/24(화)~11/25(수) 가나바이오(합성증권) — SKIP(청약 비권고)"), text);
        assertTrue(text.contains("· 상장 11/27(금) 가나테크(900001)"), text);
        assertTrue(text.contains("· 상장됨 11/12(목) 다라전자(900003) — 공모가 10,000원(확정), 배정 기록 없음, 수익 기록 없음."), text);
        assertTrue(text.indexOf("11/19(목)") < text.indexOf("11/24(화)") && text.indexOf("11/24(화)") < text.indexOf("상장 11/27"), text);
        assertTrue(text.contains("/autostock_ipo_allot") && text.contains("/autostock_ipo_return"), text);
        assertFalse(text.contains("사용법: /ipo"), "예전처럼 재판단 사용법만 답하지 않는다");
    }

    @Test
    void 일정이_많아도_플랫폼_결과_한도_3000자_안에_들고_줄인_사실을_남긴다() {
        for (int i = 0; i < 60; i++) {
            deals.save(deal("1%07d".formatted(i), "합성공모회사이름이조금긴편%02d".formatted(i), "2026102200%04d".formatted(i),
                    LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 21), null, IpoRecommendation.SKIP, null, null));
        }
        String text = run("ipo_view", "").text();

        assertTrue(text.length() < 3000, "VALUNIVO AppCommandDispatcher.MAX_RESULT_CHARS 3000(902): " + text.length());
        assertTrue(text.contains("줄(글자 한도 2800자로 줄임"), text);
        assertTrue(text.endsWith("/autostock_ipo_return 회사 매도가(또는 수익률%)"), "기록 안내 줄은 줄여도 남는다");
    }

    @Test
    void 배정_기록은_회사의_모든_신고서_행에_쓴다() {
        IpoCommandService.Reply reply = run("ipo_allot", "가나테크 3 20");

        assertTrue(reply.ok(), reply.text());
        assertTrue(reply.text().startsWith("[공모주 배정 기록] 가나테크 — 배정 3주(청약 20주) 기록. 공모가 20,000원 기준 배정 금액 약 60,000원."),
                reply.text());
        assertTrue(reply.text().contains("상장 11/27(금) 예정."), reply.text());
        List<IpoDealEntity> rows = rows("00000001");
        assertEquals(2, rows.size());
        rows.forEach(d -> {
            assertEquals(3, d.getAllocatedQty());
            assertEquals(20, d.getAppliedQty());
        });
    }

    @Test
    void 자연어와_붙여_쓴_수량도_읽는다() {
        IpoCommandService.Reply reply = run("ipo_allot", "공모주 가나 테크 배정 2주 받았어");

        assertTrue(reply.ok(), reply.text());
        rows("00000001").forEach(d -> assertEquals(2, d.getAllocatedQty()));
        assertTrue(run("ipo_allot", "가나테크 배정4주").ok());
        rows("00000001").forEach(d -> assertEquals(4, d.getAllocatedQty()));
    }

    @Test
    void 애매하거나_잘못된_배정은_거절하고_기록하지_않는다() {
        IpoCommandService.Reply ambiguous = run("ipo_allot", "가나 3");
        IpoCommandService.Reply reversed = run("ipo_allot", "가나테크 20 3");
        IpoCommandService.Reply missing = run("ipo_allot", "가나테크");
        IpoCommandService.Reply unknown = run("ipo_allot", "없는회사 3");

        assertFalse(ambiguous.ok());
        assertTrue(ambiguous.text().contains("여러 곳이 맞습니다: 가나테크, 가나바이오") || ambiguous.text().contains("여러 곳이 맞습니다: 가나바이오, 가나테크"),
                ambiguous.text());
        assertFalse(reversed.ok());
        assertTrue(reversed.text().contains("배정 20주가 청약 3주보다 많습니다"), reversed.text());
        assertFalse(missing.ok());
        assertFalse(unknown.ok());
        assertTrue(unknown.text().contains("찾지 못했습니다"), unknown.text());
        deals.findAll().forEach(d -> assertNull(d.getAllocatedQty(), d.getCorpName()));
    }

    @Test
    void 수익률로_받으면_확정_공모가로_매도가를_환산하고_차익을_답한다() {
        run("ipo_allot", "다라전자 5");
        IpoCommandService.Reply reply = run("ipo_return", "다라전자 +50%");

        assertTrue(reply.ok(), reply.text());
        assertTrue(reply.text().startsWith("[공모주 수익 기록] 다라전자 — 매도가 15,000원(수익률 50%를 확정 공모가로 환산), 매도일 11/18(수) 기록."),
                reply.text());
        assertTrue(reply.text().contains("공모가 10,000원 대비 +50.0%, 배정 5주 기준 차익 약 25,000원(수수료·세금 전)."), reply.text());
        IpoDealEntity row = rows("00000003").get(0);
        assertEquals(0, new BigDecimal("15000").compareTo(row.getSellPrice()));
        assertEquals(LocalDate.of(2026, 11, 18), row.getSellDate());
        assertTrue(run("ipo_view", "").text().contains("다라전자(900003) — 공모가 10,000원(확정), 배정 5주, 매도가 15,000원(+50.0%), 차익 약 25,000원"),
                "일정 보기의 최근 상장 줄에 기록이 보인다");
    }

    @Test
    void 매도가와_날짜를_받으면_그대로_기록하고_배정이_없으면_차익을_계산하지_않는다() {
        IpoCommandService.Reply reply = run("ipo_return", "다라전자 9,000원 2026-11-12");

        assertTrue(reply.ok(), reply.text());
        assertTrue(reply.text().contains("매도가 9,000원, 매도일 11/12(목) 기록."), reply.text());
        assertTrue(reply.text().contains("대비 -10.0%. 배정 수량 기록이 없어 차익은 계산하지 않았습니다"), reply.text());
        assertEquals(LocalDate.of(2026, 11, 12), rows("00000003").get(0).getSellDate());
    }

    @Test
    void 잘못된_수익_기록은_거절한다() {
        assertFalse(run("ipo_return", "다라전자 15000 2026-11-30").ok(), "오늘보다 뒤 매도일");
        assertFalse(run("ipo_return", "다라전자 -100%").ok(), "-100% 이하");
        assertFalse(run("ipo_return", "다라전자").ok(), "가격 없음");
        assertFalse(run("ipo_return", "가나바이오 +10%").ok(), "확정 공모가가 없으면 수익률을 환산하지 않는다");
        deals.findAll().forEach(d -> assertNull(d.getSellPrice(), d.getCorpName()));
    }

    @Test
    void 이름_속_띄어쓰기를_무시하고_이름을_뗀다() {
        assertEquals("  3 20", IpoCommandService.withoutName("가나 테크 3 20", "가나테크"));
        assertEquals(List.of(new BigDecimal("3"), new BigDecimal("20")),
                IpoCommandService.numbers("배정 3주 청약 20주").stream().map(IpoCommandService.Num::value).toList());
        assertEquals("%", IpoCommandService.numbers("+85%").get(0).unit());
    }

    @Test
    void 조립_경로_앱_봇은_기록하고_화면은_거절하며_일정은_화면에서도_보인다() {
        AppCommandRunner runner = AppCommandRunnerIpoAccess.withIpo(service());
        var direct = new AppCommandCatalog().direct("/autostock_ipo_allot 가나테크 3").orElseThrow();
        assertEquals("ipo_allot", direct.command().id());

        AppCommandRunner.Result bot = runner.run(direct.command().id(), direct.rest(), AppCommandRunner.Route.APP_BOT);
        AppCommandRunner.Result screen = runner.run("ipo_return", "다라전자 12000", AppCommandRunner.Route.APP_FE);
        AppCommandRunner.Result refused = runner.run("ipo_allot", "가나 3", AppCommandRunner.Route.PLATFORM);
        AppCommandRunner.Result view = runner.run("ipo_view", "", AppCommandRunner.Route.APP_FE);

        assertEquals("ok", bot.status(), bot.text());
        rows("00000001").forEach(d -> assertEquals(3, d.getAllocatedQty()));
        assertEquals("refused", screen.status(), "화면 경로는 변경 명령을 실행하지 않는다");
        assertNull(rows("00000003").get(0).getSellPrice());
        assertEquals("refused", refused.status(), refused.text());
        assertEquals("ok", view.status());
        assertTrue(view.text().startsWith("[공모주 일정]"), view.text());
    }
}

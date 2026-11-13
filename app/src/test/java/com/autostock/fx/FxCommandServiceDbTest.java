package com.autostock.fx;

import com.autostock.common.event.FxAlert;
import com.autostock.macrointel.EcosClient;
import com.autostock.macrointel.MacroIntelProperties;
import com.autostock.monitor.AppCommandRunner;
import com.autostock.monitor.AppCommandRunnerFxAccess;
import com.autostock.support.PostgresDataJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 외화 입력 명령 다섯과 월초·월말(설계 2·7·9장) — 실제 PostgreSQL, ECOS 대역. 값은 합성이다. 시계는 2026-12-15 10:10 KST.
 * 마지막 시험은 {@link AppCommandRunner}를 거친 조립 경로(앱 봇 실행, 화면 경로 거절)다.
 */
class FxCommandServiceDbTest extends PostgresDataJpaTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-12-15T01:10:00Z"), ZoneOffset.UTC);
    private static final FxAccumulationProperties PROPS = new FxAccumulationProperties(true, 130_000, 20_000, 20_000, 15);

    @Autowired
    FxReservationRepository reservations;

    @Autowired
    FxDirectBuyRepository buys;

    @Autowired
    FxAlertLogRepository alertLog;

    private final FakeEcos ecos = new FakeEcos();

    private FxCommandService service() {
        return new FxCommandService(PROPS, ecos, reservations, buys, CLOCK);
    }

    private FxCommandService.Reply run(String id, String text) {
        return service().run(id, text).orElseThrow();
    }

    @Test
    void 예약을_기록하면_차이와_기준선을_답하고_저장한다() {
        FxCommandService.Reply reply = run("fx_reserve", "USD 1330 40000 1360");

        assertTrue(reply.ok(), reply.text());
        assertTrue(reply.text().startsWith("예약 R"), reply.text());
        assertTrue(reply.text().contains("기준선 1,345.00"), reply.text());
        assertTrue(reply.text().contains("차이 +14.50"), reply.text());
        FxReservationEntity saved = reservations.findAll().get(0);
        assertEquals(LocalDate.of(2027, 1, 15), saved.getExpiresOn());
        assertEquals(0, new BigDecimal("14.5").compareTo(saved.getSpread()));
    }

    @Test
    void 예약_환율이_신청_당시보다_낮지_않거나_인수가_모자라면_기록하지_않는다() {
        assertFalse(run("fx_reserve", "USD 1360 40000 1360").ok());
        assertFalse(run("fx_reserve", "USD 1330 40000").ok());
        assertFalse(run("fx_reserve", "1330 40000 1360").ok());
        assertFalse(run("fx_reserve", "USD 1330 40000.5 1360").ok());
        assertEquals(0, reservations.count());
    }

    @Test
    void ECOS를_못_받아도_예약은_기록하고_검토_알림이_없다고_알린다() {
        ecos.fail = true;

        FxCommandService.Reply reply = run("fx_reserve", "엔 890 40000 905.2");

        assertTrue(reply.ok());
        assertTrue(reply.text().contains("검토 알림을 내지 않습니다"), reply.text());
    }

    @Test
    void 상한을_넘는_예약은_기록하고_경고한다() {
        run("fx_reserve", "USD 1330 100000 1360");

        FxCommandService.Reply reply = run("fx_reserve", "JPY 890 40000 905");

        assertTrue(reply.ok());
        assertTrue(reply.text().contains("상한 130,000원을 넘습니다"), reply.text());
    }

    @Test
    void 체결_직접매수_취소와_요약이_이어진다() {
        run("fx_reserve", "USD 1330 40000 1360");
        long r = reservations.findAll().get(0).getId();

        assertTrue(run("fx_fill", "R" + r).ok());
        assertFalse(run("fx_fill", "R" + r).ok(), "이미 체결");
        assertFalse(run("fx_fill", String.valueOf(r)).ok(), "숫자만은 받지 않음");
        assertTrue(run("fx_buy", "JPY 880 22000 2026-12-15").ok());
        FxCommandService.Reply view = run("fx_view", "");

        assertTrue(view.ok());
        assertTrue(view.text().contains("USD: 체결 1건 40,000원 → 30.08달러 (평균 1,330.00 원/1달러) · 직접 없음"), view.text());
        assertTrue(view.text().contains("JPY: 체결 1건 22,000원 → 2500.00엔 (평균 880.00 원/100엔)"), view.text());
        assertTrue(view.text().contains("노출 62,000 / 상한 130,000원"), view.text());

        long b = buys.findAll().get(0).getId();
        assertTrue(run("fx_cancel", "B" + b).ok());
        assertFalse(run("fx_cancel", "B" + b).ok(), "이미 취소");
        assertTrue(run("fx_view", "").text().contains("노출 40,000 / 상한"));
    }

    @Test
    void 만료일이_지난_열린_예약은_요약에서_묻는다() {
        reservations.saveAndFlush(new FxReservationEntity(FxCurrency.USD, LocalDate.of(2026, 11, 1), new BigDecimal("1360"),
                new BigDecimal("1330"), 40_000, null, null, LocalDate.of(2026, 12, 1), CLOCK.instant()));

        String text = run("fx_view", "").text();

        assertTrue(text.contains("만료일이 지난 예약 R"), text);
        assertTrue(text.contains("/autostock_fx_fill"), text);
    }

    @Test
    void 월초와_월말은_달마다_한_번만_보낸다() {
        List<Object> published = new ArrayList<>();
        FxMonthScheduler month = new FxMonthScheduler(PROPS, service(), alertLog, published::add, CLOCK);

        month.monthStart();
        month.monthStart();
        month.monthEnd();

        List<FxAlert> alerts = published.stream().map(FxAlert.class::cast).toList();
        assertEquals(List.of("MONTH_START", "MONTH_END"), alerts.stream().map(FxAlert::kind).toList());
        assertTrue(alerts.get(0).message().startsWith("[외화 월초] 2026-12"), alerts.get(0).message());
        assertTrue(alerts.get(1).message().startsWith("[외화 월말 정산] 2026-12"));
        assertTrue(alerts.stream().allMatch(a -> a.message().length() < 4096));
    }

    @Test
    void 명령_실행부를_거치면_앱_봇은_기록하고_화면_경로는_거절한다() {
        AppCommandRunner runner = AppCommandRunnerFxAccess.withFx(service());

        AppCommandRunner.Result bot = runner.run("fx_reserve", "USD 1330 40000 1360", AppCommandRunner.Route.APP_BOT);
        AppCommandRunner.Result screen = runner.run("fx_reserve", "USD 1330 40000 1360", AppCommandRunner.Route.APP_FE);
        AppCommandRunner.Result refused = runner.run("fx_buy", "USD", AppCommandRunner.Route.PLATFORM);

        assertEquals("ok", bot.status(), bot.text());
        assertEquals("refused", screen.status());
        assertEquals("refused", refused.status());
        assertEquals("ok", runner.run("fx_view", "", AppCommandRunner.Route.APP_FE).status());
        assertEquals(1, reservations.count());
    }

    /** 3인수 호출 지점만 덮어쓴 ECOS 대역(관측일 2026-12-14, USD 1345.5·JPY 892.9). */
    private static final class FakeEcos extends EcosClient {
        private boolean fail;

        FakeEcos() {
            super(WebClient.builder(), new MacroIntelProperties(true, "", "test-ecos-key", 25.0, 35.0, 1450.0, 7), CLOCK);
        }

        @Override
        protected Map<String, Object> callApi(String statCode, String itemCode, LocalDate date) {
            if (fail) {
                throw new RuntimeException("테스트 강제 실패");
            }
            String value = itemCode.equals(FxCurrency.USD.ecosItemCode()) ? "1345.5" : "892.9";
            return Map.of("StatisticSearch", Map.of("row", List.of(Map.of("TIME", "20261214", "DATA_VALUE", value))));
        }
    }
}

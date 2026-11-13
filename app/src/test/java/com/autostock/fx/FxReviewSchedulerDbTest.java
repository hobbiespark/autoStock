package com.autostock.fx;

import com.autostock.common.event.FxAlert;
import com.autostock.macrointel.EcosClient;
import com.autostock.macrointel.MacroIntelProperties;
import com.autostock.support.PostgresDataJpaTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 조립 경로(VALUNIVO RM-54): 감시 → ECOS(대역, 3인수 호출 지점) → {@link FxReviewRule} → 예약 상태·알림 기록(실제 PostgreSQL) →
 * {@link FxAlert} 발행. 값은 합성이다. 시계는 2026-12-15(화) 10:10 KST.
 */
class FxReviewSchedulerDbTest extends PostgresDataJpaTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-12-15T01:10:00Z"), ZoneOffset.UTC);
    private static final FxAccumulationProperties ON = new FxAccumulationProperties(true, 130_000, 20_000, 20_000, 15);
    private static final FxAccumulationProperties OFF = new FxAccumulationProperties(false, 130_000, 20_000, 20_000, 15);

    @Autowired
    FxReservationRepository reservations;

    @Autowired
    FxDirectBuyRepository buys;

    @Autowired
    FxAlertLogRepository alertLog;

    private final List<Object> published = new ArrayList<>();
    private final FakeEcos ecos = new FakeEcos();

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private FxReviewScheduler scheduler(FxAccumulationProperties properties) {
        return new FxReviewScheduler(properties, ecos, reservations, buys, alertLog, published::add, CLOCK);
    }

    @BeforeEach
    void reserve() {
        // 신청 당시 1360, 예약 1330 → 기준선 1345. 입력 때 ECOS 1345.5 → 차이 14.5.
        reservations.saveAndFlush(new FxReservationEntity(FxCurrency.USD, LocalDate.of(2026, 12, 1), d("1360"), d("1330"),
                40_000, d("1345.5"), LocalDate.of(2026, 11, 30), LocalDate.of(2027, 1, 1), CLOCK.instant()));
    }

    @Test
    void 조건에_닿으면_한_번_알리고_같은_관측으로는_다시_알리지_않는다() {
        ecos.rates.put("0000001", "1330.00");                       // 추정 1344.50 ≤ 1345

        scheduler(ON).reviewPass();
        scheduler(ON).reviewPass();

        List<FxAlert> alerts = published.stream().filter(FxAlert.class::isInstance).map(FxAlert.class::cast).toList();
        assertEquals(1, alerts.size());
        assertEquals("USD", alerts.get(0).currency());
        assertTrue(alerts.get(0).message().contains("추정 살 때 환율 1,344.50 원/1달러"), alerts.get(0).message());
        assertTrue(alerts.get(0).message().contains("기준선 1,345.00"), alerts.get(0).message());
        assertTrue(alerts.get(0).message().length() < 4096);
        assertEquals(1, alertLog.count());
        FxReservationEntity saved = reservations.findAll().get(0);
        assertEquals(Boolean.TRUE, saved.getLastReviewBelow());
        // 엔 예약이 없으면 엔 ECOS는 부르지 않는다(C2가 먼저). 두 번의 판정 모두 원/달러 항목으로 불렀다.
        assertEquals(List.of("731Y001/0000001", "731Y001/0000001"), ecos.calls);
    }

    @Test
    void 기준선_위면_알리지_않고_상태를_위로_남긴다() {
        ecos.rates.put("0000001", "1331.00");                       // 추정 1345.50 > 1345

        scheduler(ON).reviewPass();

        assertTrue(published.isEmpty());
        assertEquals(Boolean.FALSE, reservations.findAll().get(0).getLastReviewBelow());
    }

    @Test
    void 알림이_꺼져_있으면_일정이_아무것도_하지_않는다() {
        ecos.rates.put("0000001", "1300.00");

        scheduler(OFF).scheduledReview();

        assertTrue(published.isEmpty());
        assertTrue(ecos.calls.isEmpty());
    }

    @Test
    void ECOS를_못_받으면_알리지_않고_예외도_밖으로_내지_않는다() {
        ecos.fail = true;

        scheduler(ON).scheduledReview();

        assertTrue(published.isEmpty());
        assertEquals(0, alertLog.count());
    }

    @Test
    void 이번_달_직접매수가_있으면_알리지_않는다() {
        ecos.rates.put("0000001", "1300.00");
        buys.saveAndFlush(new FxDirectBuyEntity(FxCurrency.USD, LocalDate.of(2026, 12, 15), d("1340"), 20_000, CLOCK.instant()));

        scheduler(ON).reviewPass();

        assertTrue(published.isEmpty());
    }

    /** 3인수 호출 지점만 덮어쓴 ECOS 대역 — 항목코드별 값을 돌려준다(관측일 2026-12-14). */
    private static final class FakeEcos extends EcosClient {
        private final Map<String, String> rates = new HashMap<>();
        private final List<String> calls = new ArrayList<>();
        private boolean fail;

        FakeEcos() {
            super(WebClient.builder(), new MacroIntelProperties(true, "", "test-ecos-key", 25.0, 35.0, 1450.0, 7), CLOCK);
        }

        @Override
        protected Map<String, Object> callApi(String statCode, String itemCode, LocalDate date) {
            calls.add(statCode + "/" + itemCode);
            if (fail) {
                throw new RuntimeException("테스트 강제 실패");
            }
            String value = rates.get(itemCode);
            if (value == null) {
                return Map.of("RESULT", Map.of("CODE", "INFO-200"));
            }
            return Map.of("StatisticSearch", Map.of("row", List.of(Map.of("TIME", "20261214", "DATA_VALUE", value))));
        }
    }
}

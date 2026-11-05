package com.autostock.ipo;

import com.autostock.common.event.IpoLiveSellRecord;
import com.autostock.common.event.MarketTick;
import com.autostock.common.util.Price;
import com.autostock.common.util.StockCode;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 공모주 상장일 실거래 매도 흐름(2026-11-05, aiDoc/ipo-automation.md 8절) — 가짜 실전 창구로 확인 코드·3분할·고점 대비 하락·
 * 14:30 잔량·결과 불명·키 없음·스위치 꺼짐을 본다. 상장일은 2026-11-12(목), 공모가 16,500원, 배정 10주.
 */
class IpoLiveSellServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate LISTING = LocalDate.of(2026, 11, 12);
    private static final Instant OLD = Instant.parse("2026-10-01T00:00:00Z");
    private static final IpoLiveSellProperties ON = new IpoLiveSellProperties(true, "test-live-key", "test-live-secret");

    /** 시험용 바꿀 수 있는 시계. */
    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(int hour, int minute) {
            now = LocalDateTime.of(LISTING, java.time.LocalTime.of(hour, minute)).atZone(KST).toInstant();
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }

    /** 가짜 실전 창구 — 보낸 매도를 남기고, 정한 결과를 차례로 돌려준다. 매수 메서드는 포트에 없다. */
    static final class FakeBroker implements IpoLiveBroker {
        final List<String> sells = new ArrayList<>();
        final Deque<SellResult> results = new ArrayDeque<>();
        long sellable = 10;
        int balanceCalls;
        List<String> outstanding = List.of();
        int orderSeq = 100;

        @Override
        public SellResult sellLimit(String symbol, long quantity, BigDecimal limitPrice) {
            sells.add(symbol + " " + quantity + "@" + limitPrice.toPlainString());
            if (!results.isEmpty()) {
                return results.poll();
            }
            return new SellResult(Outcome.ACCEPTED, "000" + (orderSeq++), null);
        }

        @Override
        public long sellableQuantity(String symbol) {
            balanceCalls++;
            return sellable;
        }

        @Override
        public List<String> outstandingOrderNos(String symbol) {
            return outstanding;
        }
    }

    private final MutableClock clock = new MutableClock(Instant.EPOCH);
    private final FakeBroker broker = new FakeBroker();
    private final List<IpoLiveSellRecord> records = new ArrayList<>();
    private final List<Collection<String>> subscribed = new ArrayList<>();

    static IpoDealEntity listedDeal(int allocated) {
        IpoDealEntity deal = new IpoDealEntity("01801026", "브릴스", "20261001000476", "DART", OLD);
        deal.applyOfferingDetail(new DartClient.OfferingDetail("20261001000476", LocalDate.of(2026, 10, 30),
                LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 4), "한국투자증권", new BigDecimal("15000"), null), OLD);
        deal.classifyOffering(OfferingKind.IPO, OLD);
        deal.applyFinalPrice(new BigDecimal("16500"), new BigDecimal("14000"), new BigDecimal("16500"), OLD);
        deal.applyListing("123450", LISTING, OLD);
        deal.applyRecord(20, new BigDecimal("165000"), allocated, null, null, null, OLD);
        return deal;
    }

    private IpoLiveSellService service(IpoLiveSellProperties properties, IpoDealEntity... deals) {
        IpoDealRepository repository = mock(IpoDealRepository.class);
        when(repository.findBySubscriptionStartGreaterThanEqualOrSubscriptionStartIsNull(any())).thenReturn(List.of(deals));
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof IpoLiveSellRecord r) {
                records.add(r);
            }
        };
        return new IpoLiveSellService(repository, properties, broker, publisher, subscribed::add, Runnable::run, clock, () -> 42);
    }

    private static MarketTick tick(int hour, int minute, String price) {
        Instant at = LocalDateTime.of(LISTING, java.time.LocalTime.of(hour, minute)).atZone(KST).toInstant();
        return new MarketTick(new StockCode("123450"), new Price(new BigDecimal(price)), 1, at, MarketTick.Source.LIVE);
    }

    private List<IpoLiveSellRecord> actions(String action) {
        return records.stream().filter(r -> r.action().equals(action)).toList();
    }

    private IpoLiveSellService confirmedService(int allocated) {
        IpoLiveSellService service = service(ON, listedDeal(allocated));
        clock.set(8, 30);
        service.announce();
        clock.set(8, 40);
        assertTrue(service.confirm("0042").contains("확인됨"));
        return service;
    }

    @Test
    void 안내는_수량_분할과_4자리_코드를_보내고_시세를_구독한다() {
        IpoLiveSellService service = service(ON, listedDeal(10));
        clock.set(8, 30);
        service.announce();
        IpoLiveSellRecord plan = actions("PLAN").get(0);
        assertTrue(plan.message().contains("브릴스(123450) 배정 10주 → 3분할 1차 5주 · 2차 3주 · 3차 2주"), plan.message());
        assertTrue(plan.message().contains("1차 지정가 9,900원"), plan.message());
        assertTrue(plan.message().contains("/ipo 매도 0042"), plan.message());
        assertEquals(List.of(List.of("123450")), subscribed);
        assertTrue(broker.sells.isEmpty(), "안내만 — 주문 없음");
    }

    @Test
    void 확인_코드는_틀리면_버리고_재사용할_수_없다() {
        IpoLiveSellService service = service(ON, listedDeal(10));
        clock.set(8, 30);
        service.announce();
        clock.set(8, 31);
        assertTrue(service.confirm("9999").contains("맞지 않습니다"));
        assertTrue(service.confirm("0042").contains("코드가 없습니다"), "틀린 시도 뒤에는 원래 코드도 버려졌다");
        assertTrue(service.requestCode().contains("/ipo 매도 0042"), "새 코드를 받는다");
        assertTrue(service.confirm("0042").contains("확인됨"));
        assertTrue(service.confirm("0042").contains("이미 확인됐습니다"));
        clock.set(8, 56);
        service.placeFirst();
        assertEquals(List.of("123450 5@9900"), broker.sells, "1차 50% — 공모가 60% 지정가");
    }

    @Test
    void 확인이_08시55분을_넘기면_팔지_않고_알린다() {
        IpoLiveSellService service = service(ON, listedDeal(10));
        clock.set(8, 30);
        service.announce();
        clock.set(8, 55);
        assertTrue(service.confirm("0042").contains("지났습니다"));
        service.confirmationDeadline();
        clock.set(8, 56);
        service.placeFirst();
        service.onTick(tick(9, 0, "20000"));
        clock.set(14, 30);
        service.closeOut();
        assertTrue(broker.sells.isEmpty());
        assertTrue(actions("SKIP").stream().anyMatch(r -> r.urgent() && r.message().contains("확인이 없어")));
    }

    @Test
    void 답이_없으면_08시55분_알림_뒤_주문하지_않는다() {
        IpoLiveSellService service = service(ON, listedDeal(10));
        clock.set(8, 30);
        service.announce();
        clock.set(8, 55);
        service.confirmationDeadline();
        clock.set(8, 56);
        service.placeFirst();
        assertTrue(broker.sells.isEmpty());
        assertEquals(1, actions("SKIP").size());
    }

    @Test
    void 확정하면_3분할로_고점_대비_하락에_팔고_14시30분에_잔량을_판다() {
        IpoLiveSellService service = confirmedService(10);
        broker.sellable = 7;   // 실전 잔고가 배정보다 적다 → 7주 기준 4·2·1
        clock.set(8, 56);
        service.placeFirst();
        assertEquals(List.of("123450 4@9900"), broker.sells);

        service.onTick(tick(8, 59, "30000"));          // 장 시작 전 — 무시
        service.onTick(tick(9, 0, "20000"));           // 시초가
        service.onTick(tick(9, 1, "21000"));           // 고점
        service.onTick(tick(9, 2, "19960"));           // −4.95% — 아직
        assertEquals(1, broker.sells.size());
        service.onTick(tick(9, 3, "19950"));           // −5% → 2차
        assertEquals("123450 2@19750", broker.sells.get(1));
        service.onTick(tick(9, 4, "19900"));           // 2차는 한 번만
        assertEquals(2, broker.sells.size());
        service.onTick(tick(9, 5, "18900"));           // −10% → 3차
        assertEquals("123450 1@18710", broker.sells.get(2));
        service.onTick(tick(9, 6, "15000"));
        assertEquals(3, broker.sells.size(), "3차까지 끝나면 더 보내지 않는다");

        broker.sellable = 1;   // 남은 1주(1차 체결 일부만 등)
        clock.set(14, 30);
        service.closeOut();
        assertEquals("123450 1@14850", broker.sells.get(3), "14:30 — 마지막 체결가 15,000×0.99, 하한 14,000(시초가 70%) 이상");
        service.closeOut();
        assertEquals(4, broker.sells.size(), "14:30 잔량도 한 번만");
        assertEquals(4, actions("ORDER").size());
        assertTrue(actions("ORDER").stream().allMatch(r -> "ACCEPTED".equals(r.outcome())));
    }

    @Test
    void 장중_지정가는_시초가_70퍼센트_하한_아래로_내지_않는다() {
        IpoLiveSellService service = confirmedService(10);
        clock.set(8, 56);
        service.placeFirst();
        service.onTick(tick(9, 0, "40000"));
        service.onTick(tick(9, 1, "28000"));     // 하한가(28,000) — 28,000×0.99=27,720은 하한 아래
        assertEquals("123450 3@28000", broker.sells.get(1));
        assertEquals("123450 2@28000", broker.sells.get(2));
    }

    @Test
    void 결과_불명이나_거부는_다시_보내지_않고_알린다() {
        IpoLiveSellService service = confirmedService(10);
        broker.results.add(new IpoLiveBroker.SellResult(IpoLiveBroker.Outcome.UNKNOWN, null, "응답 시간 초과"));
        clock.set(8, 56);
        service.placeFirst();
        service.placeFirst();
        assertEquals(1, broker.sells.size(), "결과 불명 — 재주문 없음");
        IpoLiveSellRecord unknown = actions("ORDER").get(0);
        assertEquals("UNKNOWN", unknown.outcome());
        assertTrue(unknown.urgent());

        broker.results.add(new IpoLiveBroker.SellResult(IpoLiveBroker.Outcome.REJECTED, null, "[RC0000] 거부"));
        service.onTick(tick(9, 0, "20000"));
        service.onTick(tick(9, 1, "19000"));
        service.onTick(tick(9, 2, "18000"));
        assertEquals(3, broker.sells.size(), "2차 거부 뒤에도 2차는 다시 보내지 않는다(3차는 따로 한 번)");
        assertEquals("REJECTED", actions("ORDER").get(1).outcome());
    }

    @Test
    void 장마감_정리_시각_뒤_체결은_2차_3차를_만들지_않는다() {
        IpoLiveSellService service = confirmedService(10);
        clock.set(8, 56);
        service.placeFirst();
        service.onTick(tick(9, 0, "20000"));
        service.onTick(tick(14, 30, "10000"));
        assertEquals(1, broker.sells.size());
    }

    @Test
    void 미체결_목록에서_빠진_주문을_체결로_알린다() {
        IpoLiveSellService service = confirmedService(10);
        clock.set(8, 56);
        service.placeFirst();
        broker.outstanding = List.of("100");      // 앞자리 0은 무시하고 비교
        clock.set(9, 1);
        service.checkFills();
        assertTrue(actions("FILL").isEmpty());
        broker.outstanding = List.of();
        service.checkFills();
        assertEquals(1, actions("FILL").size());
        service.checkFills();
        assertEquals(1, actions("FILL").size(), "한 번만 알린다");
    }

    @Test
    void 실전_잔고가_없으면_중단한다() {
        IpoLiveSellService service = confirmedService(10);
        broker.sellable = 0;
        clock.set(8, 56);
        service.placeFirst();
        service.onTick(tick(9, 0, "20000"));
        service.onTick(tick(9, 1, "15000"));
        clock.set(14, 30);
        service.closeOut();
        assertTrue(broker.sells.isEmpty());
        assertTrue(actions("SKIP").get(0).urgent());
    }

    @Test
    void 실전_키가_없으면_꺼진_채로_하루_한_번만_안내한다() {
        IpoLiveSellService service = service(new IpoLiveSellProperties(true, "", null), listedDeal(10));
        assertFalse(service.ordersEnabled());
        clock.set(8, 30);
        service.announce();
        service.announce();
        assertEquals(1, actions("OFF").size(), "같은 날 두 번 알리지 않는다");
        IpoLiveSellRecord off = actions("OFF").get(0);
        assertTrue(off.message().contains("실전 키 없음"), off.message());
        assertTrue(off.message().contains("직접 매도"), "오늘 상장이면 직접 매도 안내");
        assertTrue(off.urgent());
        assertTrue(service.confirm("0042").contains("없습니다"));
        clock.set(8, 56);
        service.placeFirst();
        assertTrue(broker.sells.isEmpty());
        assertEquals(0, broker.balanceCalls, "키가 없으면 실전 서버를 부르지 않는다");
    }

    @Test
    void 스위치가_꺼져_있으면_키가_있어도_주문하지_않는다() {
        IpoLiveSellService service = service(new IpoLiveSellProperties(false, "test-live-key", "test-live-secret"), listedDeal(10));
        assertFalse(service.ordersEnabled());
        clock.set(8, 30);
        service.announce();
        assertTrue(actions("OFF").get(0).message().contains("ipo.live-sell.enabled=false"));
        clock.set(8, 56);
        service.placeFirst();
        clock.set(14, 30);
        service.closeOut();
        assertTrue(broker.sells.isEmpty());
        assertEquals(0, broker.balanceCalls);
    }

    @Test
    void 상장일이_아니거나_배정이_없으면_계획이_없다() {
        IpoLiveSellService service = service(ON, listedDeal(0));
        clock.set(8, 30);
        service.announce();
        assertTrue(records.isEmpty());
        assertEquals(null, service.currentDay());
    }

    @Test
    void 키_값은_문자열에_나오지_않는다() {
        assertFalse(ON.toString().contains("test-live-key"));
        assertFalse(ON.toString().contains("test-live-secret"));
    }
}

package com.autostock.execution;

import com.autostock.common.event.ProbeOrderPlaced;
import com.autostock.common.event.TrProbeCompleted;
import com.autostock.kiwoom.KiwoomApiException;
import com.autostock.kiwoom.KiwoomProperties;
import com.autostock.kiwoom.KiwoomRestClient;
import com.autostock.kiwoom.TrId;
import com.autostock.market.MarketCalendarService;
import com.autostock.risk.KillSwitch;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 주문 경로 실측(실행 계획 1.6, 매 거래일 점검) — 거래일마다 한 번, 모의 서버에서만, 결과는 계좌번호를 가려 날짜 폴더로,
 * 단계마다 기대 점검. 키움 서버 대신 상태를 가진 대역({@link FakeBroker})을 쓴다 — 주문은 미체결로 남고, 취소하면 잔량 0과
 * 취소 주문 행이 생기고, 시장가는 바로 체결된다(10/7 실측 모양).
 */
class TrProbeJobTest {

    /** 2026-10-08(목). */
    private static final LocalDate DAY = LocalDate.of(2026, 10, 8);
    /** 2026-10-08(목) 10:00:30 KST. */
    private static final Clock AT_10 = Clock.fixed(Instant.parse("2026-10-08T01:00:30Z"), ZoneOffset.UTC);

    @TempDir
    Path dir;

    private final KiwoomRestClient client = mock(KiwoomRestClient.class);
    private final KillSwitch killSwitch = mock(KillSwitch.class);
    private final MarketCalendarService calendar = mock(MarketCalendarService.class);
    private final List<Object> published = new ArrayList<>();
    /** 실측이 체결통보 처리에 알린 주문번호({@link ProbeOrderPlaced}) — 알림 이벤트와 따로 모은다. */
    private final List<String> probeOrders = new ArrayList<>();
    private final List<Duration> waits = new ArrayList<>();
    private final FakeBroker broker = new FakeBroker();

    @BeforeEach
    void setUp() {
        when(calendar.isTradingDay(any())).thenReturn(true);
        when(client.call(any(), anyString(), anyMap())).thenAnswer(inv -> broker.call(inv.getArgument(0), inv.getArgument(2)));
    }

    private TrProbeProperties properties(boolean enabled, Set<DayOfWeek> fillDays, boolean notifySuccess) {
        return new TrProbeProperties(enabled, DAY, null, LocalTime.of(9, 30), "069500", 15, dir.toString(), fillDays,
                notifySuccess);
    }

    private TrProbeJob job(TrProbeProperties properties, String baseUrl, Clock clock) {
        KiwoomProperties kiwoom = new KiwoomProperties(baseUrl, "wss://mockapi.kiwoom.com:10000/api/dostk/websocket",
                "test-key", "test-secret");
        return new TrProbeJob(properties, client, kiwoom, killSwitch, calendar, new ObjectMapper(), event -> {
            if (event instanceof ProbeOrderPlaced placed) {
                probeOrders.add(placed.brokerOrderId());
            } else {
                published.add(event);
            }
        }, clock, waits::add);
    }

    /** 체결 왕복을 하는 날(목요일)·성공도 알림 — 단언하기 쉽게. */
    private TrProbeJob mockServerJob() {
        return job(properties(true, Set.of(DayOfWeek.THURSDAY), true), "https://mockapi.kiwoom.com", AT_10);
    }

    private Path dayDir() {
        return dir.resolve("20261008");
    }

    private List<Path> files() throws Exception {
        try (Stream<Path> list = Files.list(dayDir())) {
            return list.sorted().toList();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summary() throws Exception {
        return new ObjectMapper().readValue(dayDir().resolve("tr_probe_20261008_00_summary.json").toFile(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> orderBodies(TrId trId, int calls) {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(client, times(calls)).call(eq(trId), eq("/api/dostk/ordr"), captor.capture());
        return captor.getAllValues();
    }

    private TrProbeCompleted done() {
        return (TrProbeCompleted) published.get(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 지정가_매수_매도_취소와_체결_왕복을_한_번_돌며_점검하고_결과를_날짜_폴더에_남긴다() throws Exception {
        mockServerJob().tick();

        // 지정가 매수 111,900 × 0.85 = 95,115 → 95,100, 지정가 매도 × 1.15 = 128,685 → 128,600(100원 단위), 이어서 시장가
        List<Map<String, Object>> buys = orderBodies(TrId.ORDER_BUY, 2);
        assertEquals("95100", buys.get(0).get("ord_uv"));
        assertEquals("0", buys.get(0).get("trde_tp"));
        assertEquals("1", buys.get(0).get("ord_qty"));
        assertEquals("3", buys.get(1).get("trde_tp"));
        assertEquals("", buys.get(1).get("ord_uv"));
        assertEquals("", buys.get(1).get("cond_uv"));
        List<Map<String, Object>> sells = orderBodies(TrId.ORDER_SELL, 2);
        assertEquals("128600", sells.get(0).get("ord_uv"));
        assertEquals("3", sells.get(1).get("trde_tp"));
        List<Map<String, Object>> cancels = orderBodies(TrId.ORDER_CANCEL, 2);
        assertEquals("0123456", cancels.get(0).get("orig_ord_no"));
        assertEquals("0", cancels.get(0).get("cncl_qty"));
        assertEquals("0123458", cancels.get(1).get("orig_ord_no"));
        verify(client, times(4)).call(eq(TrId.OUTSTANDING_ORDERS), anyString(), anyMap());
        verify(client, times(9)).call(eq(TrId.ORDER_EXECUTION_DETAIL), anyString(), anyMap());
        verify(client, times(4)).call(eq(TrId.EXECUTIONS), anyString(), anyMap());
        assertEquals(Collections.nCopies(6, Duration.ofSeconds(3)), waits);

        // 호출 24건 + 요약 1개, 날짜 폴더 안
        List<Path> files = files();
        assertEquals(25, files.size(), files.toString());
        Map<String, Object> summary = summary();
        assertEquals(0, summary.get("failures"));
        assertEquals(List.of(), summary.get("problems"));
        assertEquals(List.of("0123456", "0123457", "0123458", "0123459", "0123460", "0123461"), summary.get("orderNos"));
        List<Map<String, Object>> checks = (List<Map<String, Object>>) summary.get("checks");
        // 매수 10(보임·필드·표기, 주문내역 보임·필드·표기, 취소 응답, 취소 뒤 잔량, 미체결에서 빠짐, 체결 목록에 없음)
        // + 매도 8 + 체결 왕복 4(매수·매도 각각 주문내역 체결 단가·잔량, 체결 행 필드)
        assertEquals(22, checks.size(), checks.toString());
        assertTrue(checks.stream().allMatch(check -> Boolean.TRUE.equals(check.get("ok"))), checks.toString());
        Map<String, Object> scenarios = (Map<String, Object>) summary.get("scenarios");
        assertEquals("매수 1주 체결 → 매도 1주 체결", ((Map<String, Object>) scenarios.get("fill-round-trip")).get("result"));
        // 취소 주문 행(원주문 = 우리 매수)을 요약에 남긴다
        Map<String, Object> cancelRow = (Map<String, Object>) ((Map<String, Object>) scenarios.get("limit-buy")).get("cancelRow");
        assertEquals("0123457", cancelRow.get("ord_no"));
        assertEquals("취소확인", cancelRow.get("mdfy_cncl"));

        // 취소 뒤 주문내역: 원주문 행과 취소 주문 행을 함께 잡고, 남의 주문은 뺀다(결함 P-2)
        Map<String, Object> cancelled = ((List<Map<String, Object>>) summary.get("steps")).get(5);
        assertEquals("buy-cancelled", cancelled.get("label"));
        List<Map<String, Object>> ours = (List<Map<String, Object>>) cancelled.get("ourOrderRows");
        assertEquals(List.of("0123456", "0123457"), ours.stream().map(row -> row.get("ord_no")).toList());

        // 계좌번호는 가리고(공개 리포에 옮길 수 있다), 이름에 acnt가 든 목록은 통째로 가리지 않는다(결함 P-1)
        String open = Files.readString(dayDir().resolve("tr_probe_20261008_03_ka10075_buy-open.json"));
        assertTrue(open.contains("****"), open);
        assertFalse(open.contains("1234567890"), open);
        String detail = Files.readString(dayDir().resolve("tr_probe_20261008_06_kt00007_buy-cancelled.json"));
        assertTrue(detail.contains("취소확인"), detail);
        assertFalse(detail.contains("\"****\""), detail);

        assertTrue(done().success(), done().summary());
        assertEquals(DAY, done().tradeDate());
        assertTrue(done().summary().contains("지정가 매수 95,100원 0123456 취소 · 지정가 매도 128,600원 0123458 취소 · "
                + "체결 왕복 매수 1주 체결 → 매도 1주 체결. 점검 22건 중 22건 맞음"), done().summary());
    }

    @Test
    void 체결_왕복_요일이_아니면_지정가_매수_매도와_취소만_하고_정상이면_알리지_않는다() throws Exception {
        // 목요일(10/8)에 금요일 체결 왕복 — 이번 주에 빠진 체결 왕복도 없다(월요일 지정이면 빠진 것을 메운다 — 아래 테스트)
        job(properties(true, Set.of(DayOfWeek.FRIDAY), false), "https://mockapi.kiwoom.com", AT_10).tick();

        assertEquals("0", orderBodies(TrId.ORDER_BUY, 1).get(0).get("trde_tp"));
        assertEquals("0", orderBodies(TrId.ORDER_SELL, 1).get(0).get("trde_tp"));
        verify(client, times(2)).call(eq(TrId.ORDER_CANCEL), anyString(), anyMap());
        assertEquals(15, files().size());   // 호출 14건 + 요약
        assertEquals(Collections.nCopies(4, Duration.ofSeconds(3)), waits);
        assertTrue(published.isEmpty(), "점검이 모두 맞으면 텔레그램은 보내지 않는다");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 미체결에_우리_주문이_안_보이면_점검_실패로_알린다() throws Exception {
        broker.hideOutstanding = true;

        job(properties(true, Set.of(), false), "https://mockapi.kiwoom.com", AT_10).tick();

        TrProbeCompleted done = done();   // 정상 알림은 꺼 두었어도 점검 실패는 알린다
        assertFalse(done.success());
        assertTrue(done.summary().contains("점검 실패: 지정가 매수 — 미체결에 보임"), done.summary());
        List<Map<String, Object>> checks = (List<Map<String, Object>>) summary().get("checks");
        assertTrue(checks.stream().anyMatch(check -> Boolean.FALSE.equals(check.get("ok"))));
    }

    @Test
    void 주문내역_필드가_없어지면_형식_변화로_알린다() {
        broker.dropDetailField = "ord_remnq";

        job(properties(true, Set.of(), false), "https://mockapi.kiwoom.com", AT_10).tick();

        assertTrue(done().summary().contains("점검 실패: 주문내역 행 필드 — 없는 필드 [ord_remnq]"), done().summary());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 보유가_없으면_지정가_매도를_건너뛰고_실측_실패로_세지_않는다() throws Exception {
        // 10/15 09:30 실측: 10/13 국면 OFF 청산 뒤 보유 0 — kt10001이 800033으로 거부돼 "일부 실패" 알림이 나갔다
        broker.noHolding = true;

        job(properties(true, Set.of(DayOfWeek.FRIDAY), false), "https://mockapi.kiwoom.com", AT_10).tick();

        verify(client, times(1)).call(eq(TrId.ORDER_SELL), anyString(), anyMap());
        verify(client, times(1)).call(eq(TrId.ORDER_CANCEL), anyString(), anyMap());   // 지정가 매수 취소만
        Map<String, Object> summary = summary();
        assertEquals(0, summary.get("failures"));
        assertEquals(List.of(), summary.get("problems"));
        Map<String, Object> sell = (Map<String, Object>) ((Map<String, Object>) summary.get("scenarios")).get("limit-sell");
        assertEquals(TrProbeJob.NO_HOLDING_RESULT, sell.get("result"));
        assertTrue(published.isEmpty(), "보유가 없는 것은 계좌 상태다 — 실패 알림을 보내지 않는다");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 보유가_없어도_체결_왕복_날에는_산_1주로_지정가_매도와_취소를_잰다() throws Exception {
        broker.noHolding = true;

        mockServerJob().tick();

        // 매도: 지정가(거부) → 시장가 매수 체결 뒤 지정가 → 시장가
        List<Map<String, Object>> sells = orderBodies(TrId.ORDER_SELL, 3);
        assertEquals("0", sells.get(0).get("trde_tp"));
        assertEquals("0", sells.get(1).get("trde_tp"));
        assertEquals("128600", sells.get(1).get("ord_uv"));
        assertEquals("3", sells.get(2).get("trde_tp"));
        verify(client, times(2)).call(eq(TrId.ORDER_CANCEL), anyString(), anyMap());   // 지정가 매수·매도 취소
        Map<String, Object> summary = summary();
        assertEquals(0, summary.get("failures"));
        Map<String, Object> scenarios = (Map<String, Object>) summary.get("scenarios");
        Map<String, Object> sell = (Map<String, Object>) scenarios.get("limit-sell");
        assertEquals("취소", sell.get("result"));
        assertTrue(String.valueOf(sell.get("note")).contains("체결 왕복에서 산 1주"), sell.toString());
        assertEquals("매수 1주 체결 → 매도 1주 체결", ((Map<String, Object>) scenarios.get("fill-round-trip")).get("result"));
        assertTrue(done().success(), done().summary());
        // 알림 한 줄의 순서는 그대로 — 지정가 매수 · 지정가 매도 · 체결 왕복
        assertTrue(done().summary().contains("지정가 매수 95,100원 0123456 취소 · 지정가 매도 128,600원 0123459 취소 · 체결 왕복"),
                done().summary());
    }

    @Test
    void 낸_주문번호를_모두_체결통보_처리에_알린다() throws Exception {
        mockServerJob().tick();

        // 지정가 매수·취소, 지정가 매도·취소, 시장가 매수·매도 — 요약의 주문번호와 같은 순서
        assertEquals(summary().get("orderNos"), probeOrders);
        assertEquals(6, probeOrders.size());
    }

    @Test
    void 시장가_매수가_체결되지_않으면_취소하고_매도하지_않는다() {
        broker.fillMarket = false;

        mockServerJob().tick();

        // 매도는 지정가 1번뿐 — 시장가 매도 없음(원래 보유를 줄이지 않는다)
        assertEquals("0", orderBodies(TrId.ORDER_SELL, 1).get(0).get("trde_tp"));
        List<Map<String, Object>> cancels = orderBodies(TrId.ORDER_CANCEL, 3);
        assertEquals("0123460", cancels.get(2).get("orig_ord_no"));
        // 주문내역 조회: 지정가 5 + 시장가 매수 뒤 2(주문순·체결만) + 다시 보기 2 + 취소 뒤 1
        verify(client, times(5 + 2 + 2 + 1)).call(eq(TrId.ORDER_EXECUTION_DETAIL), anyString(), anyMap());
        assertFalse(done().success());
        assertTrue(done().summary().contains("체결되지 않아 취소했다(매도 안 함)"), done().summary());
    }

    @Test
    void 시장가_매도가_끝내_실패하면_보유가_늘어난_채라고_알린다() {
        broker.failMarketSell = true;

        mockServerJob().tick();

        verify(client, times(3)).call(eq(TrId.ORDER_SELL), anyString(), anyMap());   // 지정가 1 + 시장가 1 + 재시도 1
        assertFalse(done().success());
        assertTrue(done().summary().contains("1주가 늘어난 채 남음(HTS에서 매도 필요)"), done().summary());
    }

    @Test
    void 거래일마다_하루_한_번_돌고_재기동해도_같은_날은_다시_돌지_않는다() {
        TrProbeJob job = job(properties(true, Set.of(), false), "https://mockapi.kiwoom.com", AT_10);
        job.tick();
        job.tick();
        job(properties(true, Set.of(), false), "https://mockapi.kiwoom.com", AT_10).tick();   // 재기동 — 요약 파일이 있다
        verify(client, times(1)).call(eq(TrId.ORDER_BUY), anyString(), anyMap());

        // 다음 거래일(10/12 월, 휴장 10/9를 건너뜀) — 같은 작업이 다시 돈다
        TrProbeJob nextDay = job(properties(true, Set.of(), false), "https://mockapi.kiwoom.com",
                Clock.fixed(Instant.parse("2026-10-12T01:00:30Z"), ZoneOffset.UTC));
        nextDay.tick();
        verify(client, times(2)).call(eq(TrId.ORDER_BUY), anyString(), anyMap());
        assertTrue(Files.exists(dir.resolve("20261012").resolve("tr_probe_20261012_00_summary.json")));
    }

    @Test
    void 시작일_전이거나_끝난_뒤이거나_시각_밖이거나_휴장일이면_돌지_않는다() {
        job(properties(true, Set.of(), true), "https://mockapi.kiwoom.com",
                Clock.fixed(Instant.parse("2026-10-07T01:00:30Z"), ZoneOffset.UTC)).tick();   // 시작일(10/8) 전
        job(properties(true, Set.of(), true), "https://mockapi.kiwoom.com",
                Clock.fixed(Instant.parse("2026-10-08T00:29:00Z"), ZoneOffset.UTC)).tick();   // 09:29 KST
        job(properties(true, Set.of(), true), "https://mockapi.kiwoom.com",
                Clock.fixed(Instant.parse("2026-10-08T05:50:00Z"), ZoneOffset.UTC)).tick();   // 14:50 KST
        job(properties(false, Set.of(), true), "https://mockapi.kiwoom.com", AT_10).tick();   // 꺼짐
        job(new TrProbeProperties(true, DAY, DAY, LocalTime.of(9, 30), "069500", 15, dir.toString(), Set.of(), true),
                "https://mockapi.kiwoom.com", Clock.fixed(Instant.parse("2026-10-12T01:00:30Z"), ZoneOffset.UTC)).tick();   // until 뒤
        when(calendar.isTradingDay(LocalDate.of(2026, 10, 9))).thenReturn(false);
        job(properties(true, Set.of(), true), "https://mockapi.kiwoom.com",
                Clock.fixed(Instant.parse("2026-10-09T01:00:30Z"), ZoneOffset.UTC)).tick();   // 한글날 휴장

        verifyNoInteractions(client);
        assertTrue(published.isEmpty());
    }

    @Test
    void 키움_주소가_모의_서버가_아니면_절대_돌지_않는다() {
        TrProbeJob job = job(properties(true, Set.of(), true), "https://api.kiwoom.com", AT_10);
        job.tick();
        job.tick();

        verifyNoInteractions(client);
        assertTrue(published.isEmpty());
    }

    @Test
    void 킬스위치가_켜져_있으면_미루고_꺼진_뒤_돈다() {
        when(killSwitch.isEngaged()).thenReturn(true, false);
        TrProbeJob job = job(properties(true, Set.of(), true), "https://mockapi.kiwoom.com", AT_10);

        job.tick();
        verifyNoInteractions(client);

        job.tick();
        verify(client).call(eq(TrId.ORDER_BUY), anyString(), anyMap());
    }

    @Test
    void 첫_지정가_매수가_거부되면_나머지_주문을_내지_않고_실패로_알린다() throws Exception {
        broker.rejectBuy = true;

        mockServerJob().tick();

        verify(client, times(1)).call(eq(TrId.ORDER_BUY), anyString(), anyMap());
        verify(client, never()).call(eq(TrId.ORDER_SELL), anyString(), anyMap());
        verify(client, never()).call(eq(TrId.ORDER_CANCEL), anyString(), anyMap());
        verify(client, never()).call(eq(TrId.OUTSTANDING_ORDERS), anyString(), anyMap());
        assertTrue(waits.isEmpty());
        assertTrue(Files.readString(dayDir().resolve("tr_probe_20261008_02_kt10000_buy-limit.json")).contains("장종료"));
        assertFalse(done().success());
        assertTrue(done().summary().contains("지정가 매수 95,100원 접수 안 됨"), done().summary());
        assertTrue(done().summary().contains("나머지 주문을 내지 않았다"), done().summary());
    }

    @Test
    void 취소가_실패하면_한_번_더_시도한다() {
        broker.failCancelOnce = true;

        job(properties(true, Set.of(), true), "https://mockapi.kiwoom.com", AT_10).tick();

        verify(client, times(3)).call(eq(TrId.ORDER_CANCEL), anyString(), anyMap());   // 매수 실패·재시도 + 매도
        assertTrue(done().summary().contains("지정가 매수 95,100원 0123456 취소"), done().summary());
    }

    @Test
    void 조회_중_네트워크_오류가_나도_취소까지_간다() {
        broker.failDetail = true;

        job(properties(true, Set.of(), true), "https://mockapi.kiwoom.com", AT_10).tick();

        verify(client, times(2)).call(eq(TrId.ORDER_CANCEL), anyString(), anyMap());
        assertFalse(done().success());
    }

    @Test
    void 현재가를_못_읽으면_주문하지_않고_5분_간격으로_네_번까지_다시_본다() throws Exception {
        broker.noQuote = true;
        MutableClock clock = new MutableClock(Instant.parse("2026-10-08T01:00:30Z"));   // 10:00:30 KST
        TrProbeJob job = job(properties(true, Set.of(DayOfWeek.THURSDAY), true), "https://mockapi.kiwoom.com", clock);
        Path summaryFile = dayDir().resolve("tr_probe_20261008_00_summary.json");

        job.tick();                                   // 1번째 — 현재가 실패, 주문 전이라 요약·알림 없이 다시 볼 날로 남긴다
        assertTrue(published.isEmpty());
        assertFalse(Files.exists(summaryFile));
        clock.advance(Duration.ofMinutes(1));
        job.tick();                                   // 10:01:30 — 간격(5분) 전에는 조회하지 않는다
        verify(client, times(1)).call(eq(TrId.STOCK_PRICE), anyString(), anyMap());

        for (int i = 0; i < 3; i++) {                 // 10:05:30·10:10:30·10:15:30 — 2·3·4번째(마지막)
            clock.advance(Duration.ofMinutes(i == 0 ? 4 : 5));
            job.tick();
        }

        verify(client, times(4)).call(eq(TrId.STOCK_PRICE), anyString(), anyMap());
        verify(client, never()).call(eq(TrId.ORDER_BUY), anyString(), anyMap());
        assertEquals(1, published.size());
        assertFalse(done().success());
        assertTrue(done().summary().contains("현재가를 읽지 못해 주문하지 않았다(4번 시도)"), done().summary());
        assertTrue(Files.exists(summaryFile));

        clock.advance(Duration.ofMinutes(5));
        job.tick();                                   // 그날은 끝
        verify(client, times(4)).call(eq(TrId.STOCK_PRICE), anyString(), anyMap());
    }

    @Test
    void 현재가가_다시_읽히면_그_시도에서_정상으로_돈다() throws Exception {
        broker.noQuote = true;
        MutableClock clock = new MutableClock(Instant.parse("2026-10-08T01:00:30Z"));
        TrProbeJob job = job(properties(true, Set.of(DayOfWeek.THURSDAY), true), "https://mockapi.kiwoom.com", clock);

        job.tick();                                   // 10:00:30 — 실패
        broker.noQuote = false;                       // 망이 붙었다
        clock.advance(Duration.ofMinutes(5));
        job.tick();                                   // 10:05:30 — 2번째 시도에서 전부 돈다

        assertEquals(1, published.size());
        assertTrue(done().success(), done().summary());
        verify(client, times(2)).call(eq(TrId.ORDER_BUY), anyString(), anyMap());   // 지정가 1 + 체결 왕복 시장가 1
    }

    @Test
    void 마감이_가까우면_현재가_실패를_바로_실패로_남긴다() {
        broker.noQuote = true;
        TrProbeJob job = job(properties(true, Set.of(DayOfWeek.THURSDAY), true), "https://mockapi.kiwoom.com",
                Clock.fixed(Instant.parse("2026-10-08T05:46:00Z"), ZoneOffset.UTC));   // 14:46 KST — 5분 뒤면 14:50을 넘는다

        job.tick();

        assertEquals(1, published.size());
        assertTrue(done().summary().contains("현재가를 읽지 못해 주문하지 않았다(1번 시도)"), done().summary());
    }

    @Test
    void 지정가는_현재가에서_내리거나_올린_뒤_호가_단위로_내림한다() {
        assertEquals(95_100, TrProbeJob.limitPrice(new BigDecimal("111900"), 15));       // 95,115 → 100원 단위
        assertEquals(234_500, TrProbeJob.limitPrice(new BigDecimal("276000"), 15));      // 234,600 → 500원 단위
        assertEquals(28_500, TrProbeJob.limitPrice(new BigDecimal("33550"), 15));        // 28,517 → 50원 단위
        assertEquals(1_542_000, TrProbeJob.limitPrice(new BigDecimal("1815000"), 15));   // 1,542,750 → 1,000원 단위
        assertEquals(1_700, TrProbeJob.limitPrice(new BigDecimal("2000"), 15));          // 1,700 → 1원 단위
        assertEquals(128_600, TrProbeJob.limitPriceUp(new BigDecimal("111900"), 15));    // 128,685 → 100원 단위
        assertEquals(124_800, TrProbeJob.limitPriceUp(new BigDecimal("108550"), 15));    // 124,832 → 100원 단위
        assertEquals(317_000, TrProbeJob.limitPriceUp(new BigDecimal("276000"), 15));    // 317,400 → 500원 단위
    }

    @Test
    void 주문번호는_앞자리_0을_무시하고_비교한다() {
        assertTrue(TrProbeJob.sameOrderNo("0123456", "123456"));
        assertTrue(TrProbeJob.sameOrderNo("123456", "0000123456"));
        assertFalse(TrProbeJob.sameOrderNo("0123457", "0123456"));
        assertFalse(TrProbeJob.sameOrderNo("0000000", "0123456"));
        assertFalse(TrProbeJob.sameOrderNo(null, "0123456"));
        assertFalse(TrProbeJob.sameOrderNo("0123456", null));
    }

    @Test
    void 행_고르기와_체결_수량_읽기() {
        Map<String, Object> detail = Map.of("acnt_ord_cntr_prps_dtl", List.of(
                Map.of("ord_no", "0123460", "ori_ord", "0000000", "cntr_qty", "0000000001"),
                Map.of("ord_no", "0123461", "ori_ord", "0123460", "cntr_qty", "0000000000"),
                Map.of("ord_no", "0999999", "ori_ord", "0000000", "cntr_qty", "0000000005")), "return_code", 0);

        assertEquals(1, TrProbeJob.filledQuantity(detail, "0123460"));
        assertEquals(0, TrProbeJob.filledQuantity(detail, "0123462"));
        assertEquals(0, TrProbeJob.filledQuantity(null, "0123460"));
        assertEquals("0123461", TrProbeJob.childRowOf(detail, "0123460").get("ord_no"));   // 취소 주문 행
        assertNull(TrProbeJob.childRowOf(detail, "0999999"));
        assertEquals(List.of("oso_qty"), TrProbeJob.missing(Map.of("ord_no", "1"), List.of("ord_no", "oso_qty")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 계좌번호와_관리사원번호_값만_가리고_목록과_나머지는_그대로_둔다() {
        Object masked = TrProbeJob.mask(Map.of("oso", List.of(Map.of("acnt_no", "1234567890", "mang_empno", "77",
                "ord_no", "0123456", "stk_cd", "069500")), "return_code", 0));

        Map<String, Object> row = ((List<Map<String, Object>>) ((Map<String, Object>) masked).get("oso")).get(0);
        assertEquals("****", row.get("acnt_no"));
        assertEquals("****", row.get("mang_empno"));
        assertEquals("0123456", row.get("ord_no"));
        assertEquals("069500", row.get("stk_cd"));
        // 빈 값은 그대로(가린 줄 알 수 있게 "****"는 실제 값이 있을 때만)
        assertEquals("", ((Map<String, Object>) TrProbeJob.mask(Map.of("acnt_no", ""))).get("acnt_no"));
        // kt00007 목록 키에도 acnt가 들어 있다 — 목록은 남기고 안의 값만 본다(10/7 결함 P-1)
        Map<String, Object> detail = (Map<String, Object>) TrProbeJob.mask(Map.of("acnt_ord_cntr_prps_dtl",
                List.of(Map.of("ord_no", "0123456", "acnt_no", "1234567890"))));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) detail.get("acnt_ord_cntr_prps_dtl");
        assertEquals("0123456", rows.get(0).get("ord_no"));
        assertEquals("****", rows.get(0).get("acnt_no"));
    }

    @Test
    void 저장_폴더를_만들_수_없어도_예외를_던지지_않고_실패로_알린다() throws Exception {
        Path file = dir.resolve("not-a-dir");
        Files.writeString(file, "x");
        TrProbeJob job = job(new TrProbeProperties(true, DAY, null, LocalTime.of(9, 30), "069500", 15,
                file.resolve("sub").toString(), Set.of(), false), "https://mockapi.kiwoom.com", AT_10);

        job.tick();

        verify(client, never()).call(any(), anyString(), anyMap());
        assertFalse(done().success());
    }

    /**
     * 모의 서버 대역 — 10/7 실측 모양으로 답한다. 주문은 미체결로 남고(시장가는 바로 체결), 취소하면 원주문 잔량이 0이 되고 취소
     * 주문 행(원주문 = 그 주문, 정정취소 "취소확인")이 생긴다. 주문번호는 0123456부터 차례로 준다. 주문내역에는 남의 주문 0999999가
     * 섞여 있다.
     */
    private static final class FakeBroker {
        boolean fillMarket = true;
        boolean hideOutstanding;
        String dropDetailField;
        boolean failMarketSell;
        boolean failCancelOnce;
        boolean failDetail;
        boolean rejectBuy;
        boolean noQuote;
        /** 보유 0주로 시작 — 시장가로 사기 전에는 매도가 800033으로 거부된다(10/15 실측, 10/13 청산 뒤). */
        boolean noHolding;
        private int held;
        private int next = 123456;
        private final Map<String, Order> orders = new LinkedHashMap<>();

        private record Order(String no, String original, boolean buy, boolean cancelRow, int[] remaining, int[] filled) {
        }

        Map<String, Object> call(TrId tr, Map<String, Object> body) {
            return switch (tr) {
                case STOCK_PRICE -> noQuote ? Map.of("return_code", 0) : Map.of("cur_prc", "+111900", "return_code", 0);
                case ORDER_BUY, ORDER_SELL -> order(tr == TrId.ORDER_BUY, String.valueOf(body.get("trde_tp")));
                case ORDER_CANCEL -> cancel(String.valueOf(body.get("orig_ord_no")));
                case OUTSTANDING_ORDERS -> Map.of("oso", hideOutstanding ? List.of() : outstanding(), "return_code", 0);
                case ORDER_EXECUTION_DETAIL -> detail(String.valueOf(body.get("qry_tp")));
                case EXECUTIONS -> Map.of("cntr", executions(), "return_code", 0);
                default -> throw new IllegalStateException("예상 밖 TR " + tr);
            };
        }

        private String nextNo() {
            return "%07d".formatted(next++);
        }

        private Map<String, Object> order(boolean buy, String tradeType) {
            if (buy && rejectBuy) {
                throw new KiwoomApiException("키움 API 오류 [kt10000] 응답코드 20 — 모의투자 장종료");
            }
            boolean market = "3".equals(tradeType);
            if (!buy && noHolding && held < 1) {
                throw new KiwoomApiException("키움 API 논리 오류 [kt10001] [2000](800033:모의투자 매도가능수량이 부족합니다.)");
            }
            if (!buy && market && failMarketSell) {
                throw new KiwoomApiException("키움 API 오류 [kt10001] 일시 오류");
            }
            boolean filled = market && fillMarket;
            if (filled) {
                held += buy ? 1 : -1;
            }
            String no = nextNo();
            orders.put(no, new Order(no, "0000000", buy, false, new int[]{filled ? 0 : 1}, new int[]{filled ? 1 : 0}));
            return Map.of("ord_no", no, "dmst_stex_tp", "KRX", "return_code", 0, "return_msg", "모의투자 주문완료");
        }

        private Map<String, Object> cancel(String original) {
            if (failCancelOnce) {
                failCancelOnce = false;
                throw new KiwoomApiException("키움 API 오류 [kt10003] 일시 오류");
            }
            Order order = orders.get(original);
            if (order == null || order.remaining()[0] == 0) {
                throw new KiwoomApiException("키움 API 오류 [kt10003] 취소가능수량이 없습니다");
            }
            order.remaining()[0] = 0;
            String no = nextNo();
            orders.put(no, new Order(no, original, order.buy(), true, new int[]{0}, new int[]{0}));
            return Map.of("ord_no", no, "base_orig_ord_no", original, "cncl_qty", "000000000001", "return_code", 0,
                    "return_msg", "모의투자 취소주문완료");
        }

        private List<Map<String, Object>> outstanding() {
            return orders.values().stream()
                    .filter(order -> !order.cancelRow() && order.remaining()[0] > 0)
                    .map(order -> Map.<String, Object>of("acnt_no", "1234567890", "ord_no", order.no(), "stk_cd", "069500",
                            "io_tp_nm", order.buy() ? "+매수" : "-매도", "trde_tp", "보통", "ord_qty", "1", "oso_qty", "1",
                            "cntr_qty", "0", "orig_ord_no", "0000000"))
                    .toList();
        }

        private Map<String, Object> detail(String queryType) {
            if (failDetail) {
                throw new IllegalStateException("connection reset");
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Order order : orders.values()) {
                boolean open = !order.cancelRow() && order.remaining()[0] > 0;
                boolean filled = order.filled()[0] > 0;
                if (("3".equals(queryType) && !open) || ("4".equals(queryType) && !filled)) {
                    continue;
                }
                Map<String, Object> row = new HashMap<>();
                row.put("ord_no", order.no());
                row.put("ori_ord", order.original());
                row.put("stk_cd", "A069500");
                row.put("io_tp_nm", (order.buy() ? "현금매수" : "현금매도") + (order.cancelRow() ? "취소" : ""));
                row.put("ord_qty", "0000000001");
                row.put("cntr_qty", filled ? "0000000001" : "0000000000");
                row.put("cntr_uv", filled ? "0000108500" : "0000000000");
                row.put("ord_remnq", "%010d".formatted(order.remaining()[0]));
                row.put("mdfy_cncl", order.cancelRow() ? "취소확인" : "일반");
                if (dropDetailField != null) {
                    row.remove(dropDetailField);
                }
                rows.add(row);
            }
            if (!"4".equals(queryType)) {
                rows.add(Map.of("ord_no", "0999999", "ori_ord", "0000000", "io_tp_nm", "현금매수", "ord_remnq", "0000000000"));
            }
            return Map.of("acnt_ord_cntr_prps_dtl", rows, "return_code", 0, "return_msg", "모의투자 조회완료");
        }

        private List<Map<String, Object>> executions() {
            return orders.values().stream()
                    .filter(order -> order.filled()[0] > 0)
                    .map(order -> Map.<String, Object>of("ord_no", order.no(), "io_tp_nm", order.buy() ? "+매수" : "-매도",
                            "cntr_qty", "1", "cntr_pric", "108500", "ord_stt", "체결"))
                    .toList();
        }
    }

    // ── 빠진 체결 왕복은 앱이 같은 주에 메운다(2026-10-12 월요일 체결 왕복이 PC 대기로 빠짐) ─────────────

    /** 화요일(10/13) 10:00:30 KST. */
    private static final Clock TUESDAY_10 = Clock.fixed(Instant.parse("2026-10-13T01:00:30Z"), ZoneOffset.UTC);

    private void writeSummary(LocalDate day, String json) throws Exception {
        Path dayDir = dir.resolve(day.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE));
        Files.createDirectories(dayDir);
        Files.writeString(dayDir.resolve(TrProbeJob.fileName(day, 0, "summary")), json);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summaryOf(LocalDate day) throws Exception {
        Path file = dir.resolve(day.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE))
                .resolve(TrProbeJob.fileName(day, 0, "summary"));
        return new ObjectMapper().readValue(file.toFile(), Map.class);
    }

    @Test
    void 월요일_체결_왕복이_현재가_실패로_빠졌으면_같은_주_화요일에_한다() throws Exception {
        // 10/12 실측 요약 그대로 — 체결 왕복을 할 날이었지만 주문 없이 끝났다
        writeSummary(LocalDate.of(2026, 10, 12), """
                {"date":"2026-10-12","fillRoundTrip":true,"orderNos":[],"scenarios":{},"problems":["현재가를 읽지 못해 주문하지 않았다"]}""");

        job(properties(true, Set.of(DayOfWeek.MONDAY), true), "https://mockapi.kiwoom.com", TUESDAY_10).tick();

        verify(client, times(2)).call(eq(TrId.ORDER_BUY), anyString(), anyMap());   // 지정가 + 시장가(체결 왕복)
        assertEquals(true, summaryOf(LocalDate.of(2026, 10, 13)).get("fillRoundTrip"));
    }

    @Test
    void 월요일에_앱이_꺼져_요약이_없어도_화요일에_한다() throws Exception {
        job(properties(true, Set.of(DayOfWeek.MONDAY), true), "https://mockapi.kiwoom.com", TUESDAY_10).tick();

        verify(client, times(2)).call(eq(TrId.ORDER_BUY), anyString(), anyMap());
        assertEquals(true, summaryOf(LocalDate.of(2026, 10, 13)).get("fillRoundTrip"));
    }

    @Test
    void 월요일에_체결_왕복을_했으면_화요일에는_하지_않는다() throws Exception {
        writeSummary(LocalDate.of(2026, 10, 12), """
                {"date":"2026-10-12","fillRoundTrip":true,"scenarios":{"fill-round-trip":{"buyOrderNo":"0123459","result":"매수 1주 체결 → 매도 1주 체결"}}}""");

        job(properties(true, Set.of(DayOfWeek.MONDAY), true), "https://mockapi.kiwoom.com", TUESDAY_10).tick();

        verify(client, times(1)).call(eq(TrId.ORDER_BUY), anyString(), anyMap());   // 지정가만
        assertEquals(false, summaryOf(LocalDate.of(2026, 10, 13)).get("fillRoundTrip"));
    }

    @Test
    void 지난주에_빠진_체결_왕복은_메우지_않는다() throws Exception {
        // 금요일(10/16)에 할 체결 왕복이 빠졌어도 다음 주 월요일(10/19)에는 하지 않는다 — 주마다 새로 센다
        job(properties(true, Set.of(DayOfWeek.FRIDAY), true), "https://mockapi.kiwoom.com",
                Clock.fixed(Instant.parse("2026-10-19T01:00:30Z"), ZoneOffset.UTC)).tick();

        verify(client, times(1)).call(eq(TrId.ORDER_BUY), anyString(), anyMap());
        assertEquals(false, summaryOf(LocalDate.of(2026, 10, 19)).get("fillRoundTrip"));
    }

    /** 테스트가 시간을 옮기는 시계 — withZone도 같은 순간을 따라간다(UTC 고정이면 KST 시각이 틀린다). */
    private static final class MutableClock extends Clock {
        private Instant now;
        private final ZoneId zone;

        MutableClock(Instant now) {
            this(now, ZoneOffset.UTC);
        }

        private MutableClock(Instant now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            MutableClock self = this;
            return new Clock() {
                @Override
                public Instant instant() {
                    return self.now;
                }

                @Override
                public ZoneId getZone() {
                    return zone;
                }

                @Override
                public Clock withZone(ZoneId other) {
                    return self.withZone(other);
                }
            };
        }
    }
}

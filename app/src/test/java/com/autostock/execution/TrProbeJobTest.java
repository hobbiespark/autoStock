package com.autostock.execution;

import com.autostock.common.event.TrProbeCompleted;
import com.autostock.kiwoom.KiwoomApiException;
import com.autostock.kiwoom.KiwoomProperties;
import com.autostock.kiwoom.KiwoomRestClient;
import com.autostock.kiwoom.TrId;
import com.autostock.risk.KillSwitch;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 체결·미체결 TR 장중 실측(실행 계획 1.6) — 정해진 날 한 번, 모의 서버에서만, 결과는 계좌번호를 가려 파일로.
 * 키움 서버는 부르지 않는다(mock KiwoomRestClient).
 */
class TrProbeJobTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 7);
    /** 2026-10-07(수) 10:00:30 KST. */
    private static final Clock AT_10 = Clock.fixed(Instant.parse("2026-10-07T01:00:30Z"), ZoneOffset.UTC);

    @TempDir
    Path dir;

    private final KiwoomRestClient client = mock(KiwoomRestClient.class);
    private final KillSwitch killSwitch = mock(KillSwitch.class);
    private final List<Object> published = new ArrayList<>();
    private final List<Duration> waits = new ArrayList<>();

    private TrProbeProperties properties(boolean enabled) {
        return new TrProbeProperties(enabled, DAY, LocalTime.of(10, 0), "069500", 15, dir.toString());
    }

    private TrProbeJob job(TrProbeProperties properties, String baseUrl, Clock clock) {
        KiwoomProperties kiwoom = new KiwoomProperties(baseUrl, "wss://mockapi.kiwoom.com:10000/api/dostk/websocket",
                "test-key", "test-secret");
        return new TrProbeJob(properties, client, kiwoom, killSwitch, new ObjectMapper(), published::add, clock, waits::add);
    }

    private TrProbeJob mockServerJob() {
        return job(properties(true), "https://mockapi.kiwoom.com", AT_10);
    }

    /** 모의 서버 응답 대역 — 우리 주문(0123456)이 미체결·주문내역에 보이고 체결은 없다. */
    private void stubBroker() {
        when(client.call(eq(TrId.STOCK_PRICE), anyString(), anyMap()))
                .thenReturn(Map.of("cur_prc", "+111900", "return_code", 0));
        when(client.call(eq(TrId.ORDER_BUY), anyString(), anyMap()))
                .thenReturn(Map.of("ord_no", "0123456", "return_code", 0, "return_msg", "모의투자 매수주문완료"));
        when(client.call(eq(TrId.OUTSTANDING_ORDERS), anyString(), anyMap()))
                .thenReturn(Map.of("oso", List.of(Map.of("acnt_no", "1234567890", "ord_no", "0123456",
                        "io_tp_nm", "+매수", "trde_tp", "보통", "oso_qty", "1")), "return_code", 0));
        when(client.call(eq(TrId.ORDER_EXECUTION_DETAIL), anyString(), anyMap()))
                .thenReturn(Map.of("acnt_ord_cntr_prps_dtl", List.of(Map.of("ord_no", "123456", "acpt_tp", "접수",
                        "io_tp_nm", "현금매수")), "return_code", 0));
        when(client.call(eq(TrId.EXECUTIONS), anyString(), anyMap()))
                .thenReturn(Map.of("cntr", List.of(), "return_code", 0));
        when(client.call(eq(TrId.ORDER_CANCEL), anyString(), anyMap()))
                .thenReturn(Map.of("ord_no", "0123457", "base_orig_ord_no", "0123456", "return_code", 0,
                        "return_msg", "모의투자 취소주문완료"));
    }

    private List<Path> files() throws Exception {
        try (Stream<Path> list = Files.list(dir)) {
            return list.sorted().toList();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void 정해진_날_시각에_한_번_매수_조회_취소_조회하고_결과를_파일로_남긴다() throws Exception {
        stubBroker();

        mockServerJob().tick();

        // 지정가 = 111,900 × 0.85 = 95,115 → 호가 단위(100원)로 내림 95,100
        ArgumentCaptor<Map<String, Object>> buy = ArgumentCaptor.forClass(Map.class);
        verify(client).call(eq(TrId.ORDER_BUY), eq("/api/dostk/ordr"), buy.capture());
        assertEquals("95100", buy.getValue().get("ord_uv"));
        assertEquals("1", buy.getValue().get("ord_qty"));
        assertEquals("0", buy.getValue().get("trde_tp"));
        ArgumentCaptor<Map<String, Object>> cancel = ArgumentCaptor.forClass(Map.class);
        verify(client).call(eq(TrId.ORDER_CANCEL), eq("/api/dostk/ordr"), cancel.capture());
        assertEquals("0123456", cancel.getValue().get("orig_ord_no"));
        assertEquals("0", cancel.getValue().get("cncl_qty"));
        verify(client, times(7)).call(eq(TrId.OUTSTANDING_ORDERS), anyString(), anyMap());
        verify(client, times(7)).call(eq(TrId.ORDER_EXECUTION_DETAIL), anyString(), anyMap());
        verify(client, times(5)).call(eq(TrId.EXECUTIONS), anyString(), anyMap());
        assertEquals(List.of(Duration.ofSeconds(3), Duration.ofSeconds(3)), waits);

        // 호출 22건 + 요약 1개
        List<Path> files = files();
        assertEquals(23, files.size(), files.toString());
        Path summary = dir.resolve("tr_probe_20261007_00_summary.json");
        assertTrue(Files.exists(summary));
        Map<String, Object> parsed = new ObjectMapper().readValue(summary.toFile(), Map.class);
        assertEquals("0123456", parsed.get("orderNo"));
        assertEquals(95100, parsed.get("limitPrice"));
        assertEquals(0, parsed.get("failures"));
        // 미체결 7건 + 주문내역 7건에 우리 주문이 보인다(주문번호 앞자리 0 차이 무시)
        assertEquals(14, parsed.get("queriesShowingOurOrder"));

        // 계좌번호는 가려서 저장한다(공개 리포)
        String app = Files.readString(dir.resolve("tr_probe_20261007_03_ka10075_app.json"));
        assertTrue(app.contains("****"), app);
        assertFalse(app.contains("1234567890"), app);
        assertTrue(Files.readString(summary).contains("+매수"));
        assertFalse(Files.readString(summary).contains("1234567890"));

        TrProbeCompleted done = (TrProbeCompleted) published.get(0);
        assertTrue(done.success(), done.summary());
        assertEquals(DAY, done.tradeDate());
        assertTrue(done.summary().contains("95,100원 지정가"), done.summary());
    }

    @Test
    void 한_번_돌면_다음_틱과_재기동_뒤에도_다시_돌지_않는다() throws Exception {
        stubBroker();
        TrProbeJob job = mockServerJob();
        job.tick();
        job.tick();
        mockServerJob().tick();   // 재기동 — 요약 파일이 있어 돌지 않는다

        verify(client, times(1)).call(eq(TrId.ORDER_BUY), anyString(), anyMap());
        assertEquals(1, published.size());
    }

    @Test
    void 다른_날이거나_시각_전이거나_마감_전_한계를_지나면_돌지_않는다() {
        job(properties(true), "https://mockapi.kiwoom.com",
                Clock.fixed(Instant.parse("2026-10-06T01:00:30Z"), ZoneOffset.UTC)).tick();   // 하루 전
        job(properties(true), "https://mockapi.kiwoom.com",
                Clock.fixed(Instant.parse("2026-10-07T00:59:00Z"), ZoneOffset.UTC)).tick();   // 09:59 KST
        job(properties(true), "https://mockapi.kiwoom.com",
                Clock.fixed(Instant.parse("2026-10-07T05:50:00Z"), ZoneOffset.UTC)).tick();   // 14:50 KST
        job(properties(false), "https://mockapi.kiwoom.com", AT_10).tick();                   // 꺼짐

        verifyNoInteractions(client);
        assertTrue(published.isEmpty());
    }

    @Test
    void 키움_주소가_모의_서버가_아니면_절대_돌지_않는다() {
        TrProbeJob job = job(properties(true), "https://api.kiwoom.com", AT_10);
        job.tick();
        job.tick();

        verifyNoInteractions(client);
        assertTrue(published.isEmpty());
    }

    @Test
    void 킬스위치가_켜져_있으면_미루고_꺼진_뒤_돈다() {
        stubBroker();
        when(killSwitch.isEngaged()).thenReturn(true, false);
        TrProbeJob job = mockServerJob();

        job.tick();
        verifyNoInteractions(client);

        job.tick();
        verify(client).call(eq(TrId.ORDER_BUY), anyString(), anyMap());
    }

    @Test
    void 매수가_거부되면_취소하지_않고_조회만_남기고_실패로_알린다() throws Exception {
        stubBroker();
        when(client.call(eq(TrId.ORDER_BUY), anyString(), anyMap()))
                .thenThrow(new KiwoomApiException("키움 API 오류 [kt10000] 응답코드 20 — 모의투자 장종료"));

        mockServerJob().tick();

        verify(client, never()).call(eq(TrId.ORDER_CANCEL), anyString(), anyMap());
        verify(client, times(5)).call(eq(TrId.OUTSTANDING_ORDERS), anyString(), anyMap());
        assertTrue(waits.isEmpty());
        assertTrue(Files.readString(dir.resolve("tr_probe_20261007_02_kt10000_buy.json")).contains("장종료"));
        TrProbeCompleted done = (TrProbeCompleted) published.get(0);
        assertFalse(done.success());
        assertTrue(done.summary().contains("주문 접수 안 됨"), done.summary());
    }

    @Test
    void 취소가_실패하면_한_번_더_시도한다() {
        stubBroker();
        when(client.call(eq(TrId.ORDER_CANCEL), anyString(), anyMap()))
                .thenThrow(new KiwoomApiException("키움 API 오류 [kt10003] 일시 오류"))
                .thenReturn(Map.of("ord_no", "0123457", "return_code", 0, "return_msg", "모의투자 취소주문완료"));

        mockServerJob().tick();

        verify(client, times(2)).call(eq(TrId.ORDER_CANCEL), anyString(), anyMap());
        TrProbeCompleted done = (TrProbeCompleted) published.get(0);
        assertTrue(done.summary().contains("취소 모의투자 취소주문완료"), done.summary());
    }

    @Test
    void 조회_중_네트워크_오류가_나도_취소까지_간다() {
        stubBroker();
        when(client.call(eq(TrId.ORDER_EXECUTION_DETAIL), anyString(), anyMap()))
                .thenThrow(new IllegalStateException("connection reset"));

        mockServerJob().tick();

        verify(client).call(eq(TrId.ORDER_CANCEL), anyString(), anyMap());
        assertFalse(((TrProbeCompleted) published.get(0)).success());
    }

    @Test
    void 현재가를_못_읽으면_주문하지_않는다() {
        when(client.call(eq(TrId.STOCK_PRICE), anyString(), anyMap())).thenReturn(Map.of("return_code", 0));

        mockServerJob().tick();

        verify(client, never()).call(eq(TrId.ORDER_BUY), anyString(), anyMap());
        assertFalse(((TrProbeCompleted) published.get(0)).success());
    }

    @Test
    void 지정가는_현재가에서_내린_뒤_호가_단위로_내림한다() {
        assertEquals(95_100, TrProbeJob.limitPrice(new BigDecimal("111900"), 15));       // 95,115 → 100원 단위
        assertEquals(234_500, TrProbeJob.limitPrice(new BigDecimal("276000"), 15));      // 234,600 → 500원 단위
        assertEquals(28_500, TrProbeJob.limitPrice(new BigDecimal("33550"), 15));        // 28,517 → 50원 단위
        assertEquals(1_542_000, TrProbeJob.limitPrice(new BigDecimal("1815000"), 15));   // 1,542,750 → 1,000원 단위
        assertEquals(1_700, TrProbeJob.limitPrice(new BigDecimal("2000"), 15));          // 1,700 → 1원 단위
    }

    @Test
    void 주문번호는_앞자리_0을_무시하고_비교한다() {
        assertTrue(TrProbeJob.sameOrderNo("0123456", "123456"));
        assertTrue(TrProbeJob.sameOrderNo("123456", "0000123456"));
        assertFalse(TrProbeJob.sameOrderNo("0123457", "0123456"));
        assertFalse(TrProbeJob.sameOrderNo(null, "0123456"));
        assertFalse(TrProbeJob.sameOrderNo("0123456", null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 계좌번호와_관리사원번호만_가리고_나머지는_그대로_둔다() {
        Object masked = TrProbeJob.mask(Map.of("oso", List.of(Map.of("acnt_no", "1234567890", "mang_empno", "77",
                "ord_no", "0123456", "stk_cd", "069500")), "return_code", 0));

        Map<String, Object> row = ((List<Map<String, Object>>) ((Map<String, Object>) masked).get("oso")).get(0);
        assertEquals("****", row.get("acnt_no"));
        assertEquals("****", row.get("mang_empno"));
        assertEquals("0123456", row.get("ord_no"));
        assertEquals("069500", row.get("stk_cd"));
        // 빈 값은 그대로(가린 줄 알 수 있게 "****"는 실제 값이 있을 때만)
        assertEquals("", ((Map<String, Object>) TrProbeJob.mask(Map.of("acnt_no", ""))).get("acnt_no"));
    }

    @Test
    void 저장_폴더를_만들_수_없어도_예외를_던지지_않고_실패로_알린다() throws Exception {
        Path file = dir.resolve("not-a-dir");
        Files.writeString(file, "x");
        TrProbeJob job = job(new TrProbeProperties(true, DAY, LocalTime.of(10, 0), "069500", 15,
                file.resolve("sub").toString()), "https://mockapi.kiwoom.com", AT_10);

        job.tick();

        verify(client, never()).call(any(), anyString(), anyMap());
        assertFalse(((TrProbeCompleted) published.get(0)).success());
    }
}

package com.autostock.ipo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.reactive.ClientHttpConnectorSettings;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DartClient 파싱 로직 — 2026-09-11 실측 응답(한울반도체 rcept_no=20260910000583, 진코스텍
 * rcept_no=20260910000579, docs/measured/dart_estkRs_*.json)을 그대로 고정해(golden) 검증한다.
 * callList/callApi를 오버라이드해 네트워크 없이 파싱·쪽 넘기기만 확인한다(FredClient/EcosClient 테스트
 * 관례와 동일 — market.HolidaySyncService.callApi 오버라이드 패턴).
 */
class DartClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void list_json에서_증권신고서_지분증권만_필터링한다() throws Exception {
        String raw = """
                {"status":"000","message":"정상","list":[
                  {"corp_code":"01359815","corp_name":"한울반도체","stock_code":"320000","corp_cls":"K",
                   "report_nm":"[기재정정]증권신고서(지분증권)","rcept_no":"20260910000583","flr_nm":"한울반도체",
                   "rcept_dt":"20260910","rm":""},
                  {"corp_code":"00126380","corp_name":"에스케이지오센트릭","stock_code":"","corp_cls":"Y",
                   "report_nm":"증권신고서(채무증권)","rcept_no":"20260910000582","flr_nm":"에스케이지오센트릭",
                   "rcept_dt":"20260910","rm":""},
                  {"corp_code":"01158632","corp_name":"진코스텍","stock_code":"250030","corp_cls":"N",
                   "report_nm":"[기재정정]증권신고서(지분증권)","rcept_no":"20260910000579","flr_nm":"진코스텍",
                   "rcept_dt":"20260910","rm":""}
                ]}
                """;
        Map<String, Object> parsed = JSON.readValue(raw, Map.class);
        TestableDartClient client = new TestableDartClient(parsed, null);

        List<DartClient.DealNotice> deals = client.fetchRecentEquityFilings(
                LocalDate.of(2026, 8, 25), LocalDate.of(2026, 9, 11)).deals();

        assertEquals(2, deals.size(), "채무증권은 제외되고 지분증권 2건만 남아야 함");
        assertTrue(deals.stream().anyMatch(d -> "한울반도체".equals(d.corpName())));
        assertTrue(deals.stream().anyMatch(d -> "진코스텍".equals(d.corpName())));
    }

    @Test
    void list_json_조회데이터없음_status013은_예외없이_빈리스트를_반환한다() {
        Map<String, Object> parsed = Map.of("status", "013", "message", "조회된 데이타가 없습니다.");
        TestableDartClient client = new TestableDartClient(parsed, null);

        DartClient.RecentFilings filings = client.fetchRecentEquityFilings(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 11));

        assertTrue(filings.deals().isEmpty());
        assertTrue(filings.complete(), "공시가 없는 기간은 정상 — 끝까지 읽은 것이다");
    }

    // ── 2026-10-13: 모든 쪽 읽기·실패 알림(로그 점검 L-5, aiDoc/ipo-automation.md) ──────────────────────

    private static Map<String, Object> listPage(int totalPage, String... rows) throws Exception {
        String list = String.join(",", rows);
        return JSON.readValue("{\"status\":\"000\",\"message\":\"정상\",\"page_count\":100,\"total_page\":" + totalPage
                + ",\"list\":[" + list + "]}", Map.class);
    }

    private static String row(String rceptNo, String corpName, String reportName) {
        return "{\"corp_code\":\"0000" + rceptNo.substring(10) + "\",\"corp_name\":\"" + corpName + "\",\"report_nm\":\""
                + reportName + "\",\"rcept_no\":\"" + rceptNo + "\",\"rcept_dt\":\"" + rceptNo.substring(0, 8) + "\"}";
    }

    @Test
    void 모든_쪽을_읽어_지분증권_신고를_모으고_쪽_사이에_겹친_신고는_하나로_합친다() throws Exception {
        TestableDartClient client = new TestableDartClient(null, null);
        client.listPages.add(listPage(2, row("20261013000214", "에코프로비엠", "[발행조건확정]증권신고서(지분증권)"),
                row("20261013000100", "채권회사", "증권신고서(채무증권)")));
        client.listPages.add(listPage(2, row("20261008000660", "엔키화이트햇", "증권신고서(지분증권)"),
                row("20261013000214", "에코프로비엠", "[발행조건확정]증권신고서(지분증권)")));   // 넘기는 사이 새 공시로 밀려 겹침

        DartClient.RecentFilings filings = client.fetchRecentEquityFilings(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 13));

        assertEquals(List.of(1, 2), client.requestedPages);
        assertTrue(filings.complete());
        assertEquals(2, filings.pagesRead());
        assertEquals(List.of("20261013000214", "20261008000660"),
                filings.deals().stream().map(DartClient.DealNotice::rceptNo).toList());
    }

    @Test
    void 뒤쪽에서_실패하면_받은_쪽의_신고는_돌려주고_끝까지_읽지_못했다고_알린다() throws Exception {
        TestableDartClient client = new TestableDartClient(null, null);
        client.listPages.add(listPage(3, row("20261013000214", "에코프로비엠", "[발행조건확정]증권신고서(지분증권)")));
        client.listPages.add(new IllegalStateException("connection reset — https://opendart.fss.or.kr/api/list.json?crtfc_key=abc"));

        DartClient.RecentFilings filings = client.fetchRecentEquityFilings(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 13));

        assertFalse(filings.complete());
        assertEquals(1, filings.pagesRead());
        assertEquals(3, filings.totalPages());
        assertEquals(1, filings.deals().size());
        assertEquals(List.of(1, 2), client.requestedPages, "실패한 뒤로는 더 넘기지 않는다");
    }

    @Test
    void 비정상_status면_끝까지_읽지_못했다고_알린다() {
        TestableDartClient client = new TestableDartClient(Map.of("status", "020", "message", "요청 제한을 초과하였습니다."), null);

        DartClient.RecentFilings filings = client.fetchRecentEquityFilings(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 13));

        assertFalse(filings.complete());
        assertTrue(filings.deals().isEmpty());
    }

    @Test
    void estkRs_json_한울반도체_실측_응답을_파싱한다() throws Exception {
        Map<String, Object> parsed = JSON.readValue(HANWOOL_ESTKRS_RESPONSE, Map.class);
        TestableDartClient client = new TestableDartClient(null, parsed);

        Optional<DartClient.OfferingDetail> detail = client.fetchOfferingDetail(
                "01359815", "20260910000583", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 11));

        assertTrue(detail.isPresent());
        DartClient.OfferingDetail d = detail.get();
        assertEquals(LocalDate.of(2026, 11, 9), d.subscriptionStart());
        assertEquals(LocalDate.of(2026, 11, 10), d.subscriptionEnd());
        assertEquals(LocalDate.of(2026, 11, 17), d.refundDate());
        assertEquals("SK증권", d.leadManager(), "actsen=대표 인수인을 주관사로 선택해야 함");
        assertEquals(new BigDecimal("5030"), d.offerPriceConfirmed(), "slprc(5,030)의 콤마를 제거해 숫자로 파싱해야 함");
        assertEquals(3_800_000L, d.sharesOffered());
    }

    @Test
    void estkRs_json_진코스텍_실측_응답을_파싱한다_총액인수_단일주관사() throws Exception {
        Map<String, Object> parsed = JSON.readValue(JINCOSTECH_ESTKRS_RESPONSE, Map.class);
        TestableDartClient client = new TestableDartClient(null, parsed);

        Optional<DartClient.OfferingDetail> detail = client.fetchOfferingDetail(
                "01158632", "20260910000579", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 11));

        assertTrue(detail.isPresent());
        DartClient.OfferingDetail d = detail.get();
        assertEquals(LocalDate.of(2026, 10, 2), d.subscriptionStart());
        assertEquals(LocalDate.of(2026, 10, 6), d.subscriptionEnd());
        assertEquals("하나증권", d.leadManager());
        assertEquals(new BigDecimal("19500"), d.offerPriceConfirmed());
    }

    // ── 2026-10-02: 공모 종류 판정·공시 원본·회사별 목록(aiDoc/ipo-demand-forecast.md) ──────────────────

    @Test
    void 주요정보로_공모_종류를_함께_판정한다() throws Exception {
        TestableDartClient rights = new TestableDartClient(null, JSON.readValue(HANWOOL_ESTKRS_RESPONSE, Map.class));
        TestableDartClient ipo = new TestableDartClient(null, JSON.readValue(JINCOSTECH_ESTKRS_RESPONSE, Map.class));

        DartClient.OfferingLookup hanwool = rights.fetchOffering(
                "01359815", "20260910000583", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 11)).orElseThrow();
        DartClient.OfferingLookup jincostech = ipo.fetchOffering(
                "01158632", "20260910000579", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 11)).orElseThrow();

        assertEquals(OfferingKind.RIGHTS, hanwool.kind(), "주주배정후 실권주 일반공모 + 주요사항보고서 → 상장사 유상증자");
        assertEquals("주주배정후 실권주 일반공모", hanwool.offeringMethod());
        assertEquals(OfferingKind.IPO, jincostech.kind());
        assertEquals("하나증권", jincostech.detail().leadManager());
    }

    @Test
    void 이_접수번호_행이_없어도_회사의_최신_신고서로_종류는_판정한다() throws Exception {
        // [발행조건확정] 접수번호는 주요정보에 행이 없다(실측) — 상세는 비고 종류만 나온다
        TestableDartClient client = new TestableDartClient(null, JSON.readValue(JINCOSTECH_ESTKRS_RESPONSE, Map.class));

        DartClient.OfferingLookup lookup = client.fetchOffering(
                "01158632", "20260928000402", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 10, 2)).orElseThrow();

        assertNull(lookup.detail());
        assertEquals(OfferingKind.IPO, lookup.kind());
        assertTrue(client.fetchOfferingDetail("01158632", "20260928000402",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 10, 2)).isEmpty());
    }

    @Test
    void 공시_원본_ZIP에서_본문_XML을_꺼낸다() {
        TestableDartClient client = new TestableDartClient(null, null);
        client.documentResponse = DartFixtures.bytes("20260928000402.zip");

        Optional<String> xml = client.fetchDocument("20260928000402");

        assertTrue(xml.isPresent());
        assertTrue(xml.get().contains("수요예측 참여내역") || xml.get().contains("의무보유확약기간별"));
    }

    @Test
    void 공시_원본이_ZIP이_아니면_빈_값이다() {
        TestableDartClient client = new TestableDartClient(null, null);
        client.documentResponse = "{\"status\":\"014\",\"message\":\"파일이 존재하지 않습니다.\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(client.fetchDocument("20260101000000").isEmpty());
    }

    @Test
    void 회사별_목록도_증권신고서_지분증권만_돌려준다() throws Exception {
        TestableDartClient client = new TestableDartClient(null, null);
        client.corpListResponse = JSON.readValue("""
                {"status":"000","message":"정상","list":[
                  {"corp_code":"01801026","corp_name":"브릴스","stock_code":"","corp_cls":"E",
                   "report_nm":"[발행조건확정]증권신고서(지분증권)","rcept_no":"20260916000234","rcept_dt":"20260916"},
                  {"corp_code":"01801026","corp_name":"브릴스","stock_code":"","corp_cls":"E",
                   "report_nm":"투자설명서","rcept_no":"20260916000240","rcept_dt":"20260916"}
                ]}
                """, Map.class);

        List<DartClient.DealNotice> filings = client.fetchCorpEquityFilings(
                "01801026", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 2));

        assertEquals(1, filings.size());
        assertTrue(filings.get(0).reportName().contains(DartClient.CONFIRMED_TERMS_PREFIX));
    }

    /** callList/callDetail을 오버라이드해 고정 응답을 돌려주는 테스트 전용 서브클래스. */
    private static class TestableDartClient extends DartClient {
        private final Map<String, Object> listResponse;
        private final Map<String, Object> detailResponse;
        Map<String, Object> corpListResponse;
        byte[] documentResponse;
        /** 쪽별 응답(Map, 또는 던질 RuntimeException) — 비어 있으면 모든 쪽에 listResponse. */
        final List<Object> listPages = new java.util.ArrayList<>();
        final List<Integer> requestedPages = new java.util.ArrayList<>();

        TestableDartClient(Map<String, Object> listResponse, Map<String, Object> detailResponse) {
            super(WebClient.builder(), ClientHttpConnectorSettings.defaults(), new DartProperties(true, "test-key", 14));
            this.listResponse = listResponse;
            this.detailResponse = detailResponse;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected Map<String, Object> callList(LocalDate since, LocalDate until, int pageNo) {
            requestedPages.add(pageNo);
            if (listPages.isEmpty()) {
                return listResponse;
            }
            Object page = listPages.get(pageNo - 1);
            if (page instanceof RuntimeException e) {
                throw e;
            }
            return (Map<String, Object>) page;
        }

        @Override
        protected Map<String, Object> callDetail(String corpCode, LocalDate since, LocalDate until) {
            return detailResponse;
        }

        @Override
        protected Map<String, Object> callCorpList(String corpCode, LocalDate since, LocalDate until) {
            return corpListResponse;
        }

        @Override
        protected byte[] callDocument(String rceptNo) {
            return documentResponse;
        }
    }

    // 2026-09-11 실측 원문 그대로(키 미포함) — docs/measured/dart_estkRs_hanwool_20260911.json
    private static final String HANWOOL_ESTKRS_RESPONSE = """
            {"status":"000","message":"정상","group":[
              {"title":"일반사항","list":[{"rcept_no":"20260910000583","corp_cls":"K","corp_code":"01359815",
                "corp_name":"한울반도체","sbd":"2026년 11월 09일 ~ 2026년 11월 10일","pymd":"2026년 11월 17일",
                "sband":"2026년 11월 11일","asand":"2026년 11월 16일","asstd":"2026년 10월 01일","exstk":"-",
                "exprc":"-","expd":"-","rpt_rcpn":"20260901000301"}]},
              {"title":"증권의종류","list":[{"rcept_no":"20260910000583","corp_cls":"K","corp_code":"01359815",
                "corp_name":"한울반도체","stksen":"보통주","stkcnt":"3,800,000","fv":"500","slprc":"5,030",
                "slta":"19,114,000,000","slmthn":"주주배정후 실권주 일반공모"}]},
              {"title":"인수인정보","list":[
                {"rcept_no":"20260910000583","corp_cls":"K","corp_code":"01359815","corp_name":"한울반도체",
                 "stksen":"보통주","actsen":"대표","actnmn":"SK증권","udtcnt":"2,280,000","udtamt":"11,468,400,000",
                 "udtprc":"대표주관수수료 : 모집총액의 0.5%","udtmth":"잔액인수"},
                {"rcept_no":"20260910000583","corp_cls":"K","corp_code":"01359815","corp_name":"한울반도체",
                 "stksen":"보통주","actsen":"인수","actnmn":"상상인증권","udtcnt":"1,520,000","udtamt":"7,645,600,000",
                 "udtprc":"인수수수료 : 모집총액의 2.0% 中 40.0%","udtmth":"잔액인수"}]}
            ]}
            """;

    // 2026-09-11 실측 원문 그대로(키 미포함) — docs/measured/dart_estkRs_jincostech_20260911.json
    private static final String JINCOSTECH_ESTKRS_RESPONSE = """
            {"status":"000","message":"정상","group":[
              {"title":"일반사항","list":[{"rcept_no":"20260910000579","corp_cls":"N","corp_code":"01158632",
                "corp_name":"진코스텍","sbd":"2026년 10월 02일 ~ 2026년 10월 06일","pymd":"2026년 10월 08일",
                "sband":"2026년 10월 02일","asand":"2026년 10월 08일","asstd":"-","exstk":"-","exprc":"-",
                "expd":"-","rpt_rcpn":"-"}]},
              {"title":"증권의종류","list":[{"rcept_no":"20260910000579","corp_cls":"N","corp_code":"01158632",
                "corp_name":"진코스텍","stksen":"보통주","stkcnt":"852,000","fv":"500","slprc":"19,500",
                "slta":"16,614,000,000","slmthn":"일반공모"}]},
              {"title":"인수인정보","list":[{"rcept_no":"20260910000579","corp_cls":"N","corp_code":"01158632",
                "corp_name":"진코스텍","stksen":"보통주","actsen":"대표","actnmn":"하나증권","udtcnt":"852,000",
                "udtamt":"16,614,000,000","udtprc":"697,788,000","udtmth":"총액인수"}]}
            ]}
            """;
}

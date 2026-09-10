package com.autostock.ipo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DartClient 파싱 로직 — 2026-09-11 실측 응답(한울반도체 rcept_no=20260910000583, 진코스텍
 * rcept_no=20260910000579, docs/measured/dart_estkRs_*.json)을 그대로 고정해(golden) 검증한다.
 * callList/callApi를 오버라이드해 네트워크 없이 파싱만 확인한다(FredClient/EcosClient 테스트
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
                LocalDate.of(2026, 8, 25), LocalDate.of(2026, 9, 11));

        assertEquals(2, deals.size(), "채무증권은 제외되고 지분증권 2건만 남아야 함");
        assertTrue(deals.stream().anyMatch(d -> "한울반도체".equals(d.corpName())));
        assertTrue(deals.stream().anyMatch(d -> "진코스텍".equals(d.corpName())));
    }

    @Test
    void list_json_조회데이터없음_status013은_예외없이_빈리스트를_반환한다() {
        Map<String, Object> parsed = Map.of("status", "013", "message", "조회된 데이타가 없습니다.");
        TestableDartClient client = new TestableDartClient(parsed, null);

        List<DartClient.DealNotice> deals = client.fetchRecentEquityFilings(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 11));

        assertTrue(deals.isEmpty());
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

    /** callList/callDetail을 오버라이드해 고정 응답을 돌려주는 테스트 전용 서브클래스. */
    private static class TestableDartClient extends DartClient {
        private final Map<String, Object> listResponse;
        private final Map<String, Object> detailResponse;

        TestableDartClient(Map<String, Object> listResponse, Map<String, Object> detailResponse) {
            super(WebClient.builder(), new DartProperties(true, "test-key", 14));
            this.listResponse = listResponse;
            this.detailResponse = detailResponse;
        }

        @Override
        protected Map<String, Object> callList(LocalDate since, LocalDate until) {
            return listResponse;
        }

        @Override
        protected Map<String, Object> callDetail(String corpCode, LocalDate since, LocalDate until) {
            return detailResponse;
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

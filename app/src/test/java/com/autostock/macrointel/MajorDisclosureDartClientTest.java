package com.autostock.macrointel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MajorDisclosureDartClient 파싱 로직 — 2026-09-11 실측 응답(docs/measured/
 * dart_majorreport_list_20260911.json)을 golden으로 검증한다. callList를 오버라이드해
 * 네트워크 없이 파싱만 확인한다(ipo.DartClientTest와 동일 관례).
 */
class MajorDisclosureDartClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void 유상증자_CB_BW_EB_4개_유형만_필터링한다() throws Exception {
        Map<String, Object> parsed = JSON.readValue(MEASURED_RESPONSE, Map.class);
        TestableClient client = new TestableClient(parsed);

        List<MajorDisclosureDartClient.MajorDisclosureNotice> notices =
                client.fetchRecentIssuanceDecisions(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 11));

        assertEquals(5, notices.size());
        assertTrue(notices.stream().anyMatch(n -> n.type() == DisclosureType.PAID_IN_CAPITAL_INCREASE
                && "우성머티리얼스".equals(n.corpName())));
        assertTrue(notices.stream().anyMatch(n -> n.type() == DisclosureType.CONVERTIBLE_BOND
                && "CSA 코스믹".equals(n.corpName())));
        assertTrue(notices.stream().anyMatch(n -> n.type() == DisclosureType.BOND_WITH_WARRANT));
        assertTrue(notices.stream().anyMatch(n -> n.type() == DisclosureType.EXCHANGEABLE_BOND));
    }

    @Test
    void 기재정정_접두어가_붙어도_유형이_정상_판별된다() throws Exception {
        Map<String, Object> parsed = JSON.readValue(MEASURED_RESPONSE, Map.class);
        TestableClient client = new TestableClient(parsed);

        List<MajorDisclosureDartClient.MajorDisclosureNotice> notices =
                client.fetchRecentIssuanceDecisions(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 11));

        MajorDisclosureDartClient.MajorDisclosureNotice logismon = notices.stream()
                .filter(n -> "로지스몬".equals(n.corpName())).findFirst().orElseThrow();
        assertEquals(DisclosureType.PAID_IN_CAPITAL_INCREASE, logismon.type());
        assertEquals("223220", logismon.stockCode());
        assertEquals(LocalDate.of(2026, 9, 10), logismon.rceptDt());
    }

    @Test
    void status013_조회데이터없음은_예외없이_빈리스트를_반환한다() {
        Map<String, Object> parsed = Map.of("status", "013", "message", "조회된 데이타가 없습니다.");
        TestableClient client = new TestableClient(parsed);

        List<MajorDisclosureDartClient.MajorDisclosureNotice> notices =
                client.fetchRecentIssuanceDecisions(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 11));

        assertTrue(notices.isEmpty());
    }

    private static class TestableClient extends MajorDisclosureDartClient {
        private final Map<String, Object> response;

        TestableClient(Map<String, Object> response) {
            super(WebClient.builder(), new DisclosureBlacklistProperties(true, "test-key", 7, 180));
            this.response = response;
        }

        @Override
        protected Map<String, Object> callList(LocalDate since, LocalDate until) {
            return response;
        }
    }

    // 2026-09-11 실측 원문(키 미포함) — docs/measured/dart_majorreport_list_20260911.json
    private static final String MEASURED_RESPONSE = """
            {"status":"000","message":"정상","list":[
              {"corp_code":"00132868","corp_name":"우성머티리얼스","stock_code":"011300","corp_cls":"Y",
               "report_nm":"주요사항보고서(유상증자결정)","rcept_no":"20260910000580","flr_nm":"우성머티리얼스",
               "rcept_dt":"20260910","rm":""},
              {"corp_code":"00406037","corp_name":"CSA 코스믹","stock_code":"083660","corp_cls":"K",
               "report_nm":"주요사항보고서(전환사채권발행결정)","rcept_no":"20260910000549","flr_nm":"CSA 코스믹",
               "rcept_dt":"20260910","rm":""},
              {"corp_code":"01060735","corp_name":"로지스몬","stock_code":"223220","corp_cls":"K",
               "report_nm":"[기재정정]주요사항보고서(유상증자결정)","rcept_no":"20260910000536","flr_nm":"로지스몬",
               "rcept_dt":"20260910","rm":"정"},
              {"corp_code":"00132868","corp_name":"우성머티리얼스","stock_code":"011300","corp_cls":"Y",
               "report_nm":"[기재정정]주요사항보고서(신주인수권부사채권발행결정)","rcept_no":"20260429000942",
               "flr_nm":"우성머티리얼스","rcept_dt":"20260429","rm":"정"},
              {"corp_code":"00096240","corp_name":"크레버스","stock_code":"096240","corp_cls":"K",
               "report_nm":"[첨부추가]주요사항보고서(교환사채권발행결정)","rcept_no":"20260128000489","flr_nm":"크레버스",
               "rcept_dt":"20260128","rm":""}
            ]}
            """;
}

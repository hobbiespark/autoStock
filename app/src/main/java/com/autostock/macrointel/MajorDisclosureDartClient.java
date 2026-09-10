package com.autostock.macrointel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenDART {@code list.json}(공시검색) 클라이언트 — 주요사항보고서(유상증자·CB·BW·EB 발행
 * 결정)를 감시한다 (PLAN.md ADR-14, 트랙 G1).
 *
 * <h2>ipo.DartClient와 의도적으로 중복한다</h2>
 * {@code ipo.DartClient}도 같은 {@code /api/list.json} 엔드포인트를 호출하지만(공모주용,
 * {@code pblntf_ty=C} + report_nm="증권신고서(지분증권)" 필터), 이 클래스는 {@code pblntf_ty=B}
 * (주요사항보고서) + 4가지 발행결정 유형 필터로 완전히 다른 질의다. 공용화(상위 추상 클래스나
 * 유틸)로 묶으면 "쿼리 파라미터 한두 개만 다른 두 호출"처럼 보이지만, 실제로는 응답 필터링
 * 로직·도메인 타입(DealNotice vs MajorDisclosureNotice)·호출 모듈(ipo vs macrointel)이 전부
 * 다르고 macrointel → ipo 참조는 모듈 경계상 금지돼 있어(package-info.java) 상위 타입을 어느
 * 모듈에 둘지부터 애매해진다. 과도한 공통화보다 단순 복제가 낫다고 판단했다(작업 지시 원칙)
 * — WebClient 호출 배선 20줄 남짓의 중복이다.
 *
 * <h2>API 계약 (실측 확정, 2026-09-11)</h2>
 * <pre>
 *   GET /api/list.json?crtfc_key=...&amp;pblntf_ty=B&amp;bgn_de=YYYYMMDD&amp;end_de=YYYYMMDD&amp;page_count=100
 *     응답: {"status":"000","message":"정상","list":[{"corp_code","corp_name","stock_code",
 *            "corp_cls","report_nm","rcept_no","flr_nm","rcept_dt","rm"}, ...]}
 *     (ipo.DartClient와 동일 응답 스키마 — status/list 필드 구조는 list.json 공통.)
 *
 *   report_nm 예시(실측, docs/measured/dart_majorreport_list_20260911.json):
 *     "주요사항보고서(유상증자결정)", "[기재정정]주요사항보고서(전환사채권발행결정)",
 *     "[첨부정정]주요사항보고서(유상증자결정)", "주요사항보고서(신주인수권부사채권발행결정)",
 *     "[첨부추가]주요사항보고서(교환사채권발행결정)" — 유형 판별은 {@link DisclosureType#fromReportName}.
 *
 *   stock_code: 실측 범위(2026-08-01~09-11, 약 100건) 내에서는 이 4개 유형 전부 공백 없이
 *   채워져 있었으나(비상장 회사도 주요사항보고서를 낼 수 있어 이론상 공백 가능), 호출부
 *   ({@link com.autostock.macrointel.DisclosureBlacklistSyncScheduler}, 작업 지시 3항)가
 *   공백이면 스킵한다 — 블랙리스트는 종목코드 기준이라 비상장 종목은 대상이 될 수 없다.
 * </pre>
 *
 * <p>실패 처리: {@code ipo.DartClient}·{@code macrointel.FredClient}와 동일 정책 — 예외를
 * 절대 밖으로 던지지 않고 내부에서 잡아 로그만 남긴다.
 */
@Component
public class MajorDisclosureDartClient {

    private static final Logger log = LoggerFactory.getLogger(MajorDisclosureDartClient.class);

    private static final String BASE_URL = "https://opendart.fss.or.kr";
    private static final DateTimeFormatter DART_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 주요사항보고서만 대상 — 정기보고서(A)·지분공시(D) 등은 제외. */
    private static final String PBLNTF_TY_MAJOR_REPORT = "B";

    private static final String STATUS_OK = "000";
    private static final String STATUS_NO_DATA = "013";

    private final WebClient webClient;
    private final DisclosureBlacklistProperties properties;

    public MajorDisclosureDartClient(WebClient.Builder webClientBuilder, DisclosureBlacklistProperties properties) {
        this.webClient = webClientBuilder.baseUrl(BASE_URL).build();
        this.properties = properties;
    }

    /**
     * 최근 [since, until] 기간의 주요사항보고서 중 유상증자·CB·BW·EB 발행 결정 공시를 조회한다.
     * 실패 시 빈 리스트(예외를 던지지 않는다).
     */
    public List<MajorDisclosureNotice> fetchRecentIssuanceDecisions(LocalDate since, LocalDate until) {
        try {
            return parseList(callList(since, until));
        } catch (RuntimeException e) {
            log.error("DART list.json(주요사항) 조회 실패(since={}, until={})", since, until, e);
            return List.of();
        }
    }

    /** 실제 HTTP 호출 지점 — 테스트에서 오버라이드 가능(ipo.DartClient 관례). */
    protected Map<String, Object> callList(LocalDate since, LocalDate until) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/list.json")
                        .queryParam("crtfc_key", properties.dartApiKey())
                        .queryParam("pblntf_ty", PBLNTF_TY_MAJOR_REPORT)
                        .queryParam("bgn_de", since.format(DART_DATE))
                        .queryParam("end_de", until.format(DART_DATE))
                        .queryParam("page_count", 100)
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .block();
    }

    @SuppressWarnings("unchecked")
    private List<MajorDisclosureNotice> parseList(Map<String, Object> response) {
        if (response == null) {
            return List.of();
        }
        String status = String.valueOf(response.get("status"));
        if (STATUS_NO_DATA.equals(status)) {
            return List.of(); // 정상 케이스 — 해당 기간 공시 없음
        }
        if (!STATUS_OK.equals(status)) {
            log.warn("DART list.json(주요사항) 비정상 응답: status={}, message={}", status, response.get("message"));
            return List.of();
        }
        Object listObj = response.get("list");
        if (!(listObj instanceof List<?> rows)) {
            return List.of();
        }
        List<MajorDisclosureNotice> notices = new ArrayList<>();
        for (Object rowObj : rows) {
            Map<String, Object> row = (Map<String, Object>) rowObj;
            String reportName = String.valueOf(row.get("report_nm"));
            DisclosureType type = DisclosureType.fromReportName(reportName);
            if (type == null) {
                continue; // 관심 4개 유형이 아님 — 스킵
            }
            try {
                notices.add(new MajorDisclosureNotice(
                        String.valueOf(row.get("rcept_no")),
                        String.valueOf(row.get("corp_code")),
                        String.valueOf(row.get("corp_name")),
                        stringOrNull(row.get("stock_code")),
                        type,
                        reportName,
                        LocalDate.parse(String.valueOf(row.get("rcept_dt")), DART_DATE)));
            } catch (RuntimeException e) {
                log.warn("DART list.json(주요사항) 항목 파싱 실패(스킵): {}", row, e);
            }
        }
        return notices;
    }

    private static String stringOrNull(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    /** list.json 조회 결과 1건 — 유상증자/CB/BW/EB 발행 결정 공시. */
    public record MajorDisclosureNotice(String rceptNo, String corpCode, String corpName, String stockCode,
                                         DisclosureType type, String reportName, LocalDate rceptDt) {
    }
}

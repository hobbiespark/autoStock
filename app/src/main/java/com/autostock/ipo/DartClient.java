package com.autostock.ipo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenDART(금융감독원 전자공시) 클라이언트 — 신규/정정 공모주 딜 감지({@code list.json})와
 * 딜 상세({@code estkRs.json}, 증권신고서 지분증권 주요정보)를 조회한다.
 *
 * <h2>API 계약 (실측 확정, 2026-09-11 — 한울반도체 rcept_no=20260910000583, 진코스텍
 * rcept_no=20260910000579로 실제 호출. 전문은 docs/measured/dart_estkRs_*.json에 키 마스킹해
 * 보관)</h2>
 * <pre>
 *   GET /api/list.json?crtfc_key=...&amp;pblntf_ty=C&amp;bgn_de=YYYYMMDD&amp;end_de=YYYYMMDD&amp;page_count=100
 *     응답: {"status":"000","message":"정상","list":[{"corp_code","corp_name","stock_code",
 *            "corp_cls","report_nm","rcept_no","flr_nm","rcept_dt","rm"}, ...]}
 *     report_nm에 "증권신고서(지분증권)"이 포함된 건만(정정 포함 "[기재정정]증권신고서(지분증권)")
 *     신규 상장 공모로 취급한다 — 채무증권/일괄신고/소액공모 등은 이 필터로 제외된다.
 *
 *   GET /api/estkRs.json?crtfc_key=...&amp;corp_code=...&amp;bgn_de=YYYYMMDD&amp;end_de=YYYYMMDD
 *     (주의: rcept_no 단독으로는 호출 불가 — corp_code+bgn_de+end_de 필수, 실측으로 확인.
 *     corp_code는 list.json 응답의 corp_code를 그대로 쓴다.)
 *     응답: {"status":"000","group":[
 *       {"title":"일반사항","list":[{"sbd":"2026년 11월 09일 ~ 2026년 11월 10일"(청약기간),
 *          "pymd":"2026년 11월 17일"(납입/환불 기준일), "sband","asand","asstd", ...}]},
 *       {"title":"증권의종류","list":[{"stkcnt"(발행주식수),"fv"(액면가),"slprc"(모집/확정가),
 *          "slta"(모집총액),"slmthn"(모집방법)}]},
 *       {"title":"인수인정보","list":[{"actsen":"대표"|"인수","actnmn"(주관사명),...}, ...]},
 *       {"title":"자금의사용목적","list":[...]}, {"title":"매출인에관한사항","list":[...]},
 *       {"title":"일반청약자환매청구권","list":[...]}]}
 *
 *   <b>확정된 한계(과대약속 금지, ADR-9)</b>:
 *   <ul>
 *     <li>공모가 "밴드"(희망 하단/상단)는 구조화 필드로 내려오지 않는다 — "증권의종류".slprc
 *         하나만 제공된다(실측 2건 모두 단일값). 이 클라이언트는 slprc를 확정/모집가로만
 *         매핑하고 밴드 하단/상단은 항상 비워 둔다(null).</li>
 *     <li>상장(예정)일은 어느 group에도 없다 — null로 두고 수동 입력/향후 KIND 연동 과제로 남긴다.</li>
 *     <li>기관경쟁률·의무보유확약비율은 어느 group에도 없다 — 자동 수집 불가 확정,
 *         수동 입력 API(POST /api/ipo/{id}/metrics)로만 채운다.</li>
 *   </ul>
 * </pre>
 *
 * <p>실패 처리: {@code macrointel.FredClient}와 동일한 정책 — 예외를 절대 밖으로 던지지 않고
 * 내부에서 잡아 로그만 남긴다. list 조회 실패가 detail 조회를 막지 않도록 호출부
 * ({@link IpoSyncScheduler})가 딜 단위로 격리해 처리한다.
 */
@Component
public class DartClient {

    private static final Logger log = LoggerFactory.getLogger(DartClient.class);

    private static final String BASE_URL = "https://opendart.fss.or.kr";
    private static final DateTimeFormatter DART_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter KOREAN_DATE = DateTimeFormatter.ofPattern("yyyy'년' MM'월' dd'일'");
    private static final Pattern DATE_RANGE = Pattern.compile(
            "(\\d{4}년\\s*\\d{2}월\\s*\\d{2}일)\\s*~\\s*(\\d{4}년\\s*\\d{2}월\\s*\\d{2}일)");

    /** list.json report_nm 필터 — 신규/정정 증권신고서(지분증권)만 딜로 취급(ADR-9 ①). */
    static final String REPORT_NAME_FILTER = "증권신고서(지분증권)";

    private static final String STATUS_OK = "000";
    private static final String STATUS_NO_DATA = "013";

    private final WebClient webClient;
    private final DartProperties properties;

    public DartClient(WebClient.Builder webClientBuilder, DartProperties properties) {
        this.webClient = webClientBuilder.baseUrl(BASE_URL).build();
        this.properties = properties;
    }

    /**
     * 최근 [since, until] 기간의 증권신고서(지분증권) 신규/정정 건을 조회한다.
     * 실패 시 빈 리스트(예외를 던지지 않는다 — 클래스 설명 "실패 처리" 참고).
     */
    public List<DealNotice> fetchRecentEquityFilings(LocalDate since, LocalDate until) {
        try {
            return parseList(callList(since, until));
        } catch (RuntimeException e) {
            log.error("DART list.json 조회 실패(since={}, until={})", since, until, e);
            return List.of();
        }
    }

    /**
     * 지정 회사의 증권신고서(지분증권) 주요정보를 조회한다. 실측 결과 rcept_no 단독으로는
     * 조회할 수 없어(필수값 오류) corp_code+기간으로 조회한 뒤, 해당 rcept_no와 일치하는
     * 항목만 추려 반환한다. 없거나 실패 시 {@link Optional#empty()}.
     */
    public Optional<OfferingDetail> fetchOfferingDetail(String corpCode, String rceptNo,
                                                          LocalDate since, LocalDate until) {
        try {
            return parseDetail(callDetail(corpCode, since, until), rceptNo);
        } catch (RuntimeException e) {
            log.error("DART estkRs.json 조회 실패(corpCode={}, rceptNo={})", corpCode, rceptNo, e);
            return Optional.empty();
        }
    }

    /** 실제 HTTP 호출 지점 — 테스트에서 오버라이드 가능({@code market.HolidaySyncService} 관례). */
    protected Map<String, Object> callList(LocalDate since, LocalDate until) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/list.json")
                        .queryParam("crtfc_key", properties.apiKey())
                        .queryParam("pblntf_ty", "C")
                        .queryParam("bgn_de", since.format(DART_DATE))
                        .queryParam("end_de", until.format(DART_DATE))
                        .queryParam("page_count", 100)
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .block();
    }

    /** 실제 HTTP 호출 지점 — 테스트에서 오버라이드 가능. */
    protected Map<String, Object> callDetail(String corpCode, LocalDate since, LocalDate until) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/estkRs.json")
                        .queryParam("crtfc_key", properties.apiKey())
                        .queryParam("corp_code", corpCode)
                        .queryParam("bgn_de", since.format(DART_DATE))
                        .queryParam("end_de", until.format(DART_DATE))
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .block();
    }

    @SuppressWarnings("unchecked")
    private List<DealNotice> parseList(Map<String, Object> response) {
        if (response == null) {
            return List.of();
        }
        String status = String.valueOf(response.get("status"));
        if (STATUS_NO_DATA.equals(status)) {
            return List.of(); // 정상 케이스 — 해당 기간 공시 없음
        }
        if (!STATUS_OK.equals(status)) {
            log.warn("DART list.json 비정상 응답: status={}, message={}", status, response.get("message"));
            return List.of();
        }
        Object listObj = response.get("list");
        if (!(listObj instanceof List<?> rows)) {
            return List.of();
        }
        List<DealNotice> deals = new ArrayList<>();
        for (Object rowObj : rows) {
            Map<String, Object> row = (Map<String, Object>) rowObj;
            String reportName = String.valueOf(row.get("report_nm"));
            if (!reportName.contains(REPORT_NAME_FILTER)) {
                continue;
            }
            try {
                deals.add(new DealNotice(
                        String.valueOf(row.get("rcept_no")),
                        String.valueOf(row.get("corp_code")),
                        String.valueOf(row.get("corp_name")),
                        reportName,
                        LocalDate.parse(String.valueOf(row.get("rcept_dt")), DART_DATE)));
            } catch (RuntimeException e) {
                log.warn("DART list.json 항목 파싱 실패(스킵): {}", row, e);
            }
        }
        return deals;
    }

    @SuppressWarnings("unchecked")
    private Optional<OfferingDetail> parseDetail(Map<String, Object> response, String rceptNo) {
        if (response == null) {
            return Optional.empty();
        }
        String status = String.valueOf(response.get("status"));
        if (!STATUS_OK.equals(status)) {
            if (!STATUS_NO_DATA.equals(status)) {
                log.warn("DART estkRs.json 비정상 응답: status={}, message={}", status, response.get("message"));
            }
            return Optional.empty();
        }
        Object groupObj = response.get("group");
        if (!(groupObj instanceof List<?> groups)) {
            return Optional.empty();
        }
        Map<String, Object> general = findRowByTitleAndRceptNo(groups, "일반사항", rceptNo);
        Map<String, Object> security = findRowByTitleAndRceptNo(groups, "증권의종류", rceptNo);
        List<Map<String, Object>> underwriters = findRowsByTitleAndRceptNo(groups, "인수인정보", rceptNo);
        if (general == null && security == null) {
            return Optional.empty(); // 이 rcept_no 항목이 이 corp_code 조회 범위에 없음
        }

        LocalDate subStart = null;
        LocalDate subEnd = null;
        LocalDate refundDate = null;
        if (general != null) {
            String sbd = stringOrNull(general.get("sbd"));
            if (sbd != null) {
                Matcher m = DATE_RANGE.matcher(sbd);
                if (m.find()) {
                    subStart = parseKoreanDate(m.group(1));
                    subEnd = parseKoreanDate(m.group(2));
                }
            }
            refundDate = parseKoreanDate(stringOrNull(general.get("pymd")));
        }

        BigDecimal offerPriceConfirmed = null;
        Long sharesOffered = null;
        if (security != null) {
            offerPriceConfirmed = parseNumber(stringOrNull(security.get("slprc")));
            Long shares = parseLong(stringOrNull(security.get("stkcnt")));
            sharesOffered = shares;
        }

        String leadManager = underwriters.stream()
                .filter(u -> "대표".equals(stringOrNull(u.get("actsen"))))
                .map(u -> stringOrNull(u.get("actnmn")))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseGet(() -> underwriters.stream()
                        .map(u -> stringOrNull(u.get("actnmn")))
                        .filter(java.util.Objects::nonNull)
                        .findFirst()
                        .orElse(null));

        return Optional.of(new OfferingDetail(
                rceptNo, subStart, subEnd, refundDate, leadManager, offerPriceConfirmed, sharesOffered));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findRowByTitleAndRceptNo(List<?> groups, String title, String rceptNo) {
        List<Map<String, Object>> rows = findRowsByTitleAndRceptNo(groups, title, rceptNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> findRowsByTitleAndRceptNo(List<?> groups, String title, String rceptNo) {
        for (Object g : groups) {
            Map<String, Object> group = (Map<String, Object>) g;
            if (!title.equals(group.get("title"))) {
                continue;
            }
            Object listObj = group.get("list");
            if (!(listObj instanceof List<?> rows)) {
                return List.of();
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object rowObj : rows) {
                Map<String, Object> row = (Map<String, Object>) rowObj;
                if (rceptNo.equals(String.valueOf(row.get("rcept_no")))) {
                    result.add(row);
                }
            }
            return result;
        }
        return List.of();
    }

    private static String stringOrNull(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return (s.isEmpty() || "-".equals(s) || "null".equals(s)) ? null : s;
    }

    private static LocalDate parseKoreanDate(String s) {
        if (s == null) {
            return null;
        }
        try {
            return LocalDate.parse(s.replaceAll("\\s+", " ").trim(), KOREAN_DATE);
        } catch (RuntimeException e) {
            log.warn("DART 한글 날짜 파싱 실패(스킵): {}", s);
            return null;
        }
    }

    private static BigDecimal parseNumber(String s) {
        if (s == null) {
            return null;
        }
        try {
            return new BigDecimal(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLong(String s) {
        BigDecimal n = parseNumber(s);
        return n == null ? null : n.longValueExact();
    }

    /** list.json 조회 결과 1건 — 신규/정정 증권신고서(지분증권) 공시. */
    public record DealNotice(String rceptNo, String corpCode, String corpName, String reportName, LocalDate rceptDt) {
    }

    /**
     * estkRs.json 조회 결과 — 공모가 밴드는 제공되지 않아 confirmed 하나만 채워진다(클래스
     * Javadoc "확정된 한계" 참고). refundDate는 pymd(납입기준일)를 그대로 매핑한 것으로,
     * 정확히 "환불일"과 일치하는지는 실측 문서에 명시되지 않아 근사치임을 주석으로 남긴다.
     */
    public record OfferingDetail(String rceptNo, LocalDate subscriptionStart, LocalDate subscriptionEnd,
                                  LocalDate refundDate, String leadManager, BigDecimal offerPriceConfirmed,
                                  Long sharesOffered) {
    }
}

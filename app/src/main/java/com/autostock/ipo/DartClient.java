package com.autostock.ipo;

import com.autostock.common.util.SecretMasking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.reactive.ClientHttpConnectorBuilder;
import org.springframework.boot.http.client.reactive.ClientHttpConnectorSettings;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;

import javax.net.ssl.SSLContext;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
 *     <li>기관경쟁률·의무보유확약비율은 estkRs.json 어느 group에도 없다 — 2026-10-02부터 공시 원본
 *         ({@code document.xml})의 [발행조건확정] 수요예측 결과에서 읽는다({@link DemandForecastParser}).</li>
 *     <li>estkRs.json은 회사의 최신 증권신고서(기재정정 포함) 한 건만 돌려주고 [발행조건확정] 접수번호로는 행이 없다.
 *         조회 기간은 그 최신 신고서의 접수일에 걸려야 한다(실측 2026-10-02 — 진코스텍: 10/1 하루 조회는 없음, 8/1~10/2는
 *         9/10 기재정정 행). 일부 회사는 기간과 관계없이 조회되지 않는다(툴젠·빅웨이브로보틱스 등).</li>
 *   </ul>
 *
 *   GET /api/document.xml?crtfc_key=...&amp;rcept_no=...   (공시서류원본파일, 실측 2026-10-02 — 14건)
 *     응답: ZIP 한 개, 안에 {rcept_no}.xml(dart4.xsd 형식, UTF-8). 압축 5~120KB, 풀면 30KB~1MB.
 *     파일이 없으면 ZIP 대신 오류 응답(status 014 등)이 온다.
 *
 *   GET /api/list.json?crtfc_key=...&amp;corp_code=...&amp;pblntf_ty=C&amp;bgn_de=...&amp;end_de=...
 *     회사 한 곳의 발행공시 — [발행조건확정] 신고서를 찾을 때 쓴다(corp_code가 없으면 기간이 3개월로 제한된다).
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

    /** [발행조건확정] 신고서 — 수요예측 결과가 실리는 정정 신고서(report_nm 접두). */
    static final String CONFIRMED_TERMS_PREFIX = "[발행조건확정]";

    private static final String STATUS_OK = "000";
    private static final String STATUS_NO_DATA = "013";
    /** 공시 원본 ZIP 응답 상한 — WebClient 기본(256KB)으로는 큰 신고서가 잘린다. */
    private static final int MAX_DOCUMENT_BYTES = 16 * 1024 * 1024;
    /** 압축을 푼 XML 상한 — 비정상적으로 큰 응답(압축 폭탄)을 끝까지 풀지 않는다. */
    private static final long MAX_XML_BYTES = 32L * 1024 * 1024;
    private static final Pattern XML_ENCODING = Pattern.compile("encoding=\"([A-Za-z0-9_-]+)\"");

    private final WebClient webClient;
    private final DartProperties properties;

    public DartClient(WebClient.Builder webClientBuilder, ClientHttpConnectorSettings connectorSettings,
                      DartProperties properties) {
        this.webClient = webClientBuilder.baseUrl(BASE_URL).clientConnector(jdkCipherConnector(connectorSettings))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_DOCUMENT_BYTES))
                .build();
        this.properties = properties;
    }

    /**
     * opendart.fss.or.kr 용 HTTP 커넥터 — 암호 스위트를 Netty 기본 목록이 아닌 <b>JDK 기본 목록</b>으로 둔다.
     *
     * <p>실측 2026-09-18(자택망): 이 경로의 DART 서버는 TLS 1.3을 거부(protocol_version)하고 TLS 1.2에서
     * {@code TLS_DHE_RSA_WITH_AES_128_CBC_SHA256} 같은 DHE 계열만 협상한다. Reactor Netty의 기본 암호 목록은
     * ECDHE·TLS_RSA 계열뿐이고(DHE 없음), JDK 21은 TLS_RSA_*를 비활성화해 두어 서버와 겹치는 스위트가 0개 →
     * handshake_failure. 순수 JDK SSLSocket(scripts/TlsProbe.java)은 JDK 기본 목록에 DHE가 있어 성공한다.
     * 신뢰 저장소·프로토콜 협상은 JDK 기본 그대로다.
     *
     * <p>커넥터를 교체하면 Boot 자동구성 커넥터의 타임아웃이 빠지므로, 같은 설정(spring.http.reactiveclient.*)으로
     * 빌드하고 TLS만 customizer로 얹는다(customizer는 설정 적용 뒤에 실행된다).
     */
    private static ClientHttpConnector jdkCipherConnector(ClientHttpConnectorSettings settings) {
        try {
            String[] jdkCiphers = SSLContext.getDefault().getDefaultSSLParameters().getCipherSuites();
            var ssl = SslContextBuilder.forClient()
                    .ciphers(Arrays.asList(jdkCiphers), SupportedCipherSuiteFilter.INSTANCE)
                    .build();
            return ClientHttpConnectorBuilder.reactor()
                    .withHttpClientCustomizer(client -> client.secure(spec -> spec.sslContext(ssl)))
                    .build(settings);
        } catch (GeneralSecurityException | javax.net.ssl.SSLException e) {
            throw new IllegalStateException("DART용 SslContext 생성 실패", e);
        }
    }

    /**
     * 최근 [since, until] 기간의 증권신고서(지분증권) 신규/정정 건을 조회한다.
     * 실패 시 빈 리스트(예외를 던지지 않는다 — 클래스 설명 "실패 처리" 참고).
     */
    public List<DealNotice> fetchRecentEquityFilings(LocalDate since, LocalDate until) {
        try {
            return parseList(callList(since, until));
        } catch (RuntimeException e) {
            // 예외 메시지에 crtfc_key가 담긴 전체 URL이 그대로 들어있을 수 있어(WebClientResponseException
            // 등) 마스킹을 거친 뒤 로그로 남긴다(스택트레이스는 보존, 메시지만 마스킹) — SecretMasking 참고.
            log.error("DART list.json 조회 실패(since={}, until={})", since, until, SecretMasking.sanitizeForLogging(e));
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
        return fetchOffering(corpCode, rceptNo, since, until).map(OfferingLookup::detail);
    }

    /**
     * 주요정보 조회 한 번으로 이 접수번호의 상세(있으면)와 공모 종류(회사의 최신 신고서 기준, 판정되면)를 함께 돌려준다.
     * 둘 다 없거나 실패하면 {@link Optional#empty()}.
     */
    public Optional<OfferingLookup> fetchOffering(String corpCode, String rceptNo, LocalDate since, LocalDate until) {
        try {
            return parseOffering(callDetail(corpCode, since, until), rceptNo);
        } catch (RuntimeException e) {
            // 위와 동일한 이유로 마스킹 후 로그(crtfc_key 유출 방지).
            log.error("DART estkRs.json 조회 실패(corpCode={}, rceptNo={})", corpCode, rceptNo,
                    SecretMasking.sanitizeForLogging(e));
            return Optional.empty();
        }
    }

    /** 회사 한 곳의 [since, until] 증권신고서(지분증권) 계열 공시 — [발행조건확정] 찾기용. 실패하면 빈 목록. */
    public List<DealNotice> fetchCorpEquityFilings(String corpCode, LocalDate since, LocalDate until) {
        try {
            return parseList(callCorpList(corpCode, since, until));
        } catch (RuntimeException e) {
            log.error("DART list.json(회사별) 조회 실패(corpCode={})", corpCode, SecretMasking.sanitizeForLogging(e));
            return List.of();
        }
    }

    /**
     * 공시 원본(document.xml ZIP)의 본문 XML. 파일이 없거나(ZIP이 아닌 오류 응답) 실패하면 빈 값.
     * 압축 안의 {@code {rcept_no}.xml}을 우선 쓰고, 없으면 첫 XML을 쓴다. 글자 인코딩은 XML 선언을 따른다(기본 UTF-8).
     */
    public Optional<String> fetchDocument(String rceptNo) {
        try {
            byte[] body = callDocument(rceptNo);
            if (body == null || body.length < 4 || body[0] != 'P' || body[1] != 'K') {
                log.warn("DART document.xml 원본 없음(rceptNo={}): {}", rceptNo, preview(body));
                return Optional.empty();
            }
            return unzipMainXml(body, rceptNo);
        } catch (IOException | RuntimeException e) {
            log.error("DART document.xml 조회 실패(rceptNo={})", rceptNo, SecretMasking.sanitizeForLogging(e));
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

    /** 실제 HTTP 호출 지점 — 테스트에서 오버라이드 가능. 회사 한 곳의 발행공시 목록. */
    protected Map<String, Object> callCorpList(String corpCode, LocalDate since, LocalDate until) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/list.json")
                        .queryParam("crtfc_key", properties.apiKey())
                        .queryParam("corp_code", corpCode)
                        .queryParam("pblntf_ty", "C")
                        .queryParam("bgn_de", since.format(DART_DATE))
                        .queryParam("end_de", until.format(DART_DATE))
                        .queryParam("page_count", 100)
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .block();
    }

    /** 실제 HTTP 호출 지점 — 테스트에서 오버라이드 가능. 공시 원본 ZIP 바이트(오류면 JSON/XML 본문). */
    protected byte[] callDocument(String rceptNo) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/document.xml")
                        .queryParam("crtfc_key", properties.apiKey())
                        .queryParam("rcept_no", rceptNo)
                        .build())
                .retrieve()
                .bodyToMono(byte[].class)
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
    private Optional<OfferingLookup> parseOffering(Map<String, Object> response, String rceptNo) {
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
        OfferingDetail detail = parseDetail(groups, rceptNo);
        // 공모 종류는 이 접수번호 행이 없어도(예: [발행조건확정]) 회사의 최신 신고서 행으로 판정한다 — 같은 공모다
        Map<String, Object> general = firstRow(groups, "일반사항", rceptNo);
        String method = offeringMethod(groups, rceptNo);
        OfferingKind kind = OfferingKind.fromOfferingInfo(method,
                general == null ? null : stringOrNull(general.get("rpt_rcpn")),
                general == null ? null : stringOrNull(general.get("asstd")));
        if (detail == null && kind == null) {
            return Optional.empty();
        }
        return Optional.of(new OfferingLookup(detail, kind, method));
    }

    /** 이 접수번호의 상세 — 행이 없으면(이 corp_code 조회 범위에 없음) null. */
    private OfferingDetail parseDetail(List<?> groups, String rceptNo) {
        Map<String, Object> general = findRowByTitleAndRceptNo(groups, "일반사항", rceptNo);
        Map<String, Object> security = findRowByTitleAndRceptNo(groups, "증권의종류", rceptNo);
        List<Map<String, Object>> underwriters = findRowsByTitleAndRceptNo(groups, "인수인정보", rceptNo);
        if (general == null && security == null) {
            return null; // 이 rcept_no 항목이 이 corp_code 조회 범위에 없음
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

        return new OfferingDetail(
                rceptNo, subStart, subEnd, refundDate, leadManager, offerPriceConfirmed, sharesOffered);
    }

    /** "증권의종류"의 모집방법(slmthn) — 이 접수번호의 보통주 행을 먼저, 없으면 아무 행. */
    @SuppressWarnings("unchecked")
    private String offeringMethod(List<?> groups, String rceptNo) {
        List<Map<String, Object>> rows = allRows(groups, "증권의종류");
        return rows.stream()
                .sorted(java.util.Comparator
                        .comparing((Map<String, Object> r) -> !rceptNo.equals(String.valueOf(r.get("rcept_no"))))
                        .thenComparing(r -> !"보통주".equals(stringOrNull(r.get("stksen")))))
                .map(r -> stringOrNull(r.get("slmthn")))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /** title 그룹에서 이 접수번호 행, 없으면 첫 행. */
    private Map<String, Object> firstRow(List<?> groups, String title, String rceptNo) {
        Map<String, Object> exact = findRowByTitleAndRceptNo(groups, title, rceptNo);
        if (exact != null) {
            return exact;
        }
        List<Map<String, Object>> rows = allRows(groups, title);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> allRows(List<?> groups, String title) {
        for (Object g : groups) {
            Map<String, Object> group = (Map<String, Object>) g;
            if (title.equals(group.get("title")) && group.get("list") instanceof List<?> rows) {
                List<Map<String, Object>> result = new ArrayList<>();
                for (Object row : rows) {
                    result.add((Map<String, Object>) row);
                }
                return result;
            }
        }
        return List.of();
    }

    /** ZIP에서 본문 XML을 꺼낸다 — {rcept_no}.xml 우선, 없으면 첫 XML. */
    private static Optional<String> unzipMainXml(byte[] zip, String rceptNo) throws IOException {
        byte[] chosen = null;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".xml")) {
                    continue;
                }
                boolean main = name.equals(rceptNo + ".xml");
                if (chosen == null || main) {
                    chosen = readLimited(in);
                }
                if (main) {
                    break;
                }
            }
        }
        if (chosen == null) {
            log.warn("DART document.xml ZIP에 XML이 없음(rceptNo={})", rceptNo);
            return Optional.empty();
        }
        return Optional.of(new String(chosen, xmlCharset(chosen)));
    }

    private static byte[] readLimited(ZipInputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) > 0) {
            total += n;
            if (total > MAX_XML_BYTES) {
                throw new IOException("공시 원본 XML이 " + MAX_XML_BYTES + "바이트를 넘음");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /** XML 선언의 encoding(없거나 모르면 UTF-8) — 오래된 공시는 EUC-KR일 수 있다. */
    private static Charset xmlCharset(byte[] xml) {
        String head = new String(xml, 0, Math.min(xml.length, 200), StandardCharsets.ISO_8859_1);
        Matcher m = XML_ENCODING.matcher(head);
        if (m.find()) {
            try {
                return Charset.forName(m.group(1));
            } catch (RuntimeException e) {
                log.warn("DART 공시 원본의 모르는 인코딩 {} — UTF-8로 읽는다", m.group(1));
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String preview(byte[] body) {
        if (body == null) {
            return "(빈 응답)";
        }
        String s = new String(body, 0, Math.min(body.length, 200), StandardCharsets.UTF_8);
        return s.replaceAll("\\s+", " ").trim();
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

    /**
     * 주요정보 조회 결과 — 이 접수번호의 상세(행이 없으면 null)와 공모 종류(판정 못 하면 null).
     *
     * @param offeringMethod 모집방법 원문(slmthn, 예: "일반공모", "주주배정후 실권주 일반공모") — 로그·근거용
     */
    public record OfferingLookup(OfferingDetail detail, OfferingKind kind, String offeringMethod) {
    }
}

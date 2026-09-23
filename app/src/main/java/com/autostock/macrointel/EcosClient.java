package com.autostock.macrointel;

import com.autostock.common.util.MarketConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ECOS(한국은행 경제통계시스템) 클라이언트 — 원/달러 환율({@code 731Y001})·기준금리
 * ({@code 722Y001})의 최신 관측치 1건을 조회한다.
 *
 * <h2>API 계약(문서 기반 추정, TODO 실측)</h2>
 * <pre>
 *   GET https://ecos.bok.or.kr/api/StatisticSearch/{key}/json/kr/1/1/{통계코드}/D/{시작일}/{종료일}
 *
 *   응답: {"StatisticSearch":{"row":[{"TIME":"20260817","DATA_VALUE":"1345.50"}]}}
 * </pre>
 * <b>확정 필요 사항(TODO 실측)</b>:
 * <ul>
 *   <li>주기 코드 — 이 클래스는 일별(D)로 가정했다. 실제로는 통계표별로 주기가 고정돼
 *       있어(예: 기준금리는 월 1회 변경) D로 조회 시 응답이 비거나, 혹은 통계표 자체가
 *       D를 지원하지 않을 수 있다 — 최초 호출 결과로 확인 후 필요하면 M(월)로 전환.</li>
 *   <li>항목코드(item code) — ECOS의 일부 통계표는 국가·품목별 세부 항목이 있어 URL
 *       마지막에 item1(코드1) 파라미터가 추가로 필요할 수 있다. 731Y001(환율)은 통화별
 *       item code(예: 0000001=미국달러)가 필요하다는 문서상 정황이 있으나, 이 클래스는
 *       아직 item code를 붙이지 않았다 — 실제 응답으로 확인 후 {@link #callApi} 수정 필요.</li>
 *   <li>날짜 포맷(YYYYMMDD)도 문서 기반 추정이다.</li>
 * </ul>
 *
 * <p>실패 처리 정책은 {@link FredClient}와 동일 — 예외를 절대 밖으로 던지지 않는다.
 */
@Component
public class EcosClient {

    private static final Logger log = LoggerFactory.getLogger(EcosClient.class);

    private static final String BASE_URL = "https://ecos.bok.or.kr";
    private static final DateTimeFormatter ECOS_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 원/달러 환율(매매기준율) 통계코드. */
    public static final String STAT_CODE_USDKRW = "731Y001";
    /** 731Y001의 항목코드 — 원/미국달러(매매기준율). 실측 2026-09-18: 항목 없이 조회하면 43개 통화 중 첫 행만 오므로 명시한다. */
    public static final String ITEM_CODE_USDKRW = "0000001";
    /** 한국은행 기준금리 통계코드. */
    public static final String STAT_CODE_BASE_RATE = "722Y001";
    /** 722Y001의 항목코드 — 한국은행 기준금리. 실측 2026-09-18: 항목 없이 오늘 1일만 조회하면 {@code RESULT INFO-200}(데이터 없음). */
    public static final String ITEM_CODE_BASE_RATE = "0101000";
    /** 조회 범위(일). 실측: 기준금리는 당일 미발표가 흔하고 환율도 휴일엔 비어 오므로 최근 N일을 받아 마지막 행을 쓴다. */
    private static final int LOOKBACK_DAYS = 14;

    private final WebClient webClient;
    private final MacroIntelProperties properties;

    public EcosClient(WebClient.Builder webClientBuilder, MacroIntelProperties properties) {
        this.webClient = webClientBuilder.baseUrl(BASE_URL).build();
        this.properties = properties;
    }

    /** 지정한 통계코드의 오늘(KST)자 최신값을 조회한다. 실패·결측이면 {@link Optional#empty()}. */
    public Optional<Observation> fetchLatest(String statCode) {
        LocalDate today = LocalDate.now(MarketConstants.KST);
        try {
            Map<String, Object> response = callApi(statCode, today);
            return parse(statCode, response);
        } catch (RuntimeException e) {
            log.error("ECOS 조회 실패(statCode={})", statCode, e);
            return Optional.empty();
        }
    }

    /**
     * 실제 HTTP 호출 지점 — protected로 열어 두어 테스트에서 이 메서드를 오버라이드해
     * 준비된 응답으로 대체할 수 있게 했다({@code market.HolidaySyncService.callApi}와 같은
     * 이 저장소의 기존 관례).
     */
    protected Map<String, Object> callApi(String statCode, LocalDate date) {
        // 실측 확정(2026-09-18, docs/measured/ext_probe_20260918_ecos_*.json):
        //   GET /api/StatisticSearch/{key}/json/kr/1/20/{statCode}/D/{start}/{end}/{itemCode}
        //   → {"StatisticSearch":{"list_total_count":N,"row":[{"TIME":"20260918","DATA_VALUE":"1380.3",...}]}}
        //   데이터 없음 → {"RESULT":{"CODE":"INFO-200","MESSAGE":"해당하는 데이터가 없습니다."}}
        String end = date.format(ECOS_DATE);
        String start = date.minusDays(LOOKBACK_DAYS).format(ECOS_DATE);
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/StatisticSearch/{key}/json/kr/1/20/{statCode}/D/{start}/{end}/{item}")
                        .build(properties.ecosApiKey(), statCode, start, end, itemCodeFor(statCode)))
                .retrieve()
                .bodyToMono(Map.class)
                .block();
    }

    private static String itemCodeFor(String statCode) {
        return switch (statCode) {
            case STAT_CODE_USDKRW -> ITEM_CODE_USDKRW;
            case STAT_CODE_BASE_RATE -> ITEM_CODE_BASE_RATE;
            default -> throw new IllegalArgumentException("항목코드 미정의 통계코드: " + statCode);
        };
    }

    @SuppressWarnings("unchecked")
    private Optional<Observation> parse(String statCode, Map<String, Object> response) {
        if (response == null) {
            return Optional.empty();
        }
        try {
            Map<String, Object> search = (Map<String, Object>) response.get("StatisticSearch");
            if (search == null) {
                // 실측: 데이터가 없으면 StatisticSearch 대신 RESULT{CODE:"INFO-200"}가 온다 — 정상적인 "값 없음".
                // 그 외 CODE(키 오류 등)는 원인 파악을 위해 warn으로 남긴다.
                Object result = response.get("RESULT");
                if (result instanceof Map<?, ?> r && "INFO-200".equals(String.valueOf(r.get("CODE")))) {
                    log.info("ECOS {} 최근 {}일 데이터 없음 — 이번 수집 스킵", statCode, LOOKBACK_DAYS);
                } else {
                    log.warn("ECOS {} 응답에 StatisticSearch 없음: {}", statCode, response);
                }
                return Optional.empty();
            }
            Object rowObj = search.get("row");
            List<Map<String, Object>> rows;
            if (rowObj instanceof List<?> list) {
                rows = (List<Map<String, Object>>) list;
            } else if (rowObj instanceof Map<?, ?> single) {
                // 공공 API 계열에서 흔한 패턴 — 결과가 1건이면 배열이 아니라 단일 객체로 오는
                // 경우가 있다(HolidaySyncService.parseItems와 같은 방어 — 실측에선 항상 배열이었음).
                rows = List.of((Map<String, Object>) single);
            } else {
                return Optional.empty();
            }
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            // 마지막 행이 최신값(TIME 오름차순, 실측 확인) — 최근 14일 범위 조회이므로 여러 건이 온다.
            Map<String, Object> last = rows.get(rows.size() - 1);
            LocalDate date = LocalDate.parse(String.valueOf(last.get("TIME")), ECOS_DATE);
            BigDecimal value = new BigDecimal(String.valueOf(last.get("DATA_VALUE")));
            return Optional.of(new Observation(date, value));
        } catch (RuntimeException e) {
            log.error("ECOS 응답 파싱 실패: {}", response, e);
            return Optional.empty();
        }
    }

    /** ECOS 관측치 한 건. */
    public record Observation(LocalDate date, BigDecimal value) {
    }
}

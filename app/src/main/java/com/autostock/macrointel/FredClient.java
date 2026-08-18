package com.autostock.macrointel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * FRED(세인트루이스 연준 경제통계) 클라이언트 — VIX({@code VIXCLS})·달러인덱스
 * ({@code DTWEXBGS})의 최신 관측치 1건을 조회한다.
 *
 * <h2>API 계약(문서 기반 추정, TODO 실측)</h2>
 * <pre>
 *   GET https://api.stlouisfed.org/fred/series/observations
 *     ?series_id={VIXCLS|DTWEXBGS}&amp;api_key=...&amp;file_type=json&amp;sort_order=desc&amp;limit=1
 *
 *   응답: {"observations":[{"date":"2026-08-17","value":"18.52"}]}
 * </pre>
 * FRED는 결측치(휴장일 등)를 값 문자열 {@code "."}로 내려주는 것으로 알려져 있다(문서 기반,
 * 실제 키로 확인 전이라 확정 아님) — {@link #parse}에서 방어적으로 걸러낸다.
 *
 * <p>실패 처리: 이 클라이언트는 예외를 절대 밖으로 던지지 않는다({@link #fetchLatest}가
 * 내부에서 잡아 error 로그만 남기고 {@link Optional#empty()}를 반환) — 호출부인
 * {@code MacroSyncScheduler}가 "FRED 실패가 ECOS 수집을 막지 않는다"는 소스별 격리
 * 정책을 지키려면, 이 클라이언트 자체가 먼저 조용히 실패해야 하기 때문이다
 * ({@code market.HolidaySyncService}의 부분 실패 정책과 같은 설계).
 */
@Component
public class FredClient {

    private static final Logger log = LoggerFactory.getLogger(FredClient.class);

    private static final String BASE_URL = "https://api.stlouisfed.org";

    /** VIX(변동성지수) series id. */
    public static final String SERIES_VIX = "VIXCLS";
    /** 달러인덱스(Trade Weighted U.S. Dollar Index: Broad, Goods and Services) series id. */
    public static final String SERIES_DXY = "DTWEXBGS";

    private final WebClient webClient;
    private final MacroIntelProperties properties;

    public FredClient(WebClient.Builder webClientBuilder, MacroIntelProperties properties) {
        this.webClient = webClientBuilder.baseUrl(BASE_URL).build();
        this.properties = properties;
    }

    /**
     * 지정한 series의 최신 관측치 1건을 조회한다. 실패·결측이면 {@link Optional#empty()}를
     * 반환한다(예외를 던지지 않는다 — 클래스 설명 "실패 처리" 참고).
     */
    public Optional<Observation> fetchLatest(String seriesId) {
        try {
            Map<String, Object> response = callApi(seriesId);
            return parse(seriesId, response);
        } catch (RuntimeException e) {
            log.error("FRED 조회 실패(series={}) — TODO 실측 전 예상 구현이라 실제 원인 미확인", seriesId, e);
            return Optional.empty();
        }
    }

    /**
     * 실제 HTTP 호출 지점 — protected로 열어 두어 테스트에서 이 메서드를 오버라이드해
     * 준비된 응답으로 대체할 수 있게 했다({@code market.HolidaySyncService.callApi}와 같은
     * 이 저장소의 기존 관례).
     */
    protected Map<String, Object> callApi(String seriesId) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/fred/series/observations")
                        .queryParam("series_id", seriesId)
                        .queryParam("api_key", properties.fredApiKey())
                        .queryParam("file_type", "json")
                        .queryParam("sort_order", "desc")
                        .queryParam("limit", 1)
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .block(); // TODO 실측: 타임아웃/재시도 정책은 실제 응답 지연 확인 후 결정
    }

    @SuppressWarnings("unchecked")
    private Optional<Observation> parse(String seriesId, Map<String, Object> response) {
        if (response == null) {
            return Optional.empty();
        }
        try {
            Object obsObj = response.get("observations");
            if (!(obsObj instanceof List<?> list) || list.isEmpty()) {
                return Optional.empty(); // 해당 기간에 관측치가 없음 — 정상 케이스로 취급
            }
            Map<String, Object> first = (Map<String, Object>) list.get(0);
            String dateStr = String.valueOf(first.get("date"));
            String valueStr = String.valueOf(first.get("value"));
            if ("null".equals(valueStr) || ".".equals(valueStr) || valueStr.isBlank()) {
                // FRED 결측치 표기(문서 기반, TODO 실측) — 이번 수집은 스킵하고 다음 배치를 기다린다.
                log.warn("FRED {} 최신 관측치가 결측('.') — 이번 수집 스킵", seriesId);
                return Optional.empty();
            }
            return Optional.of(new Observation(LocalDate.parse(dateStr), new BigDecimal(valueStr)));
        } catch (RuntimeException e) {
            log.error("FRED 응답 파싱 실패 — 예상 포맷과 다름(TODO 실측 필요): {}", response, e);
            return Optional.empty();
        }
    }

    /** FRED 관측치 한 건. */
    public record Observation(LocalDate date, BigDecimal value) {
    }
}

package com.autostock.macrointel;

import com.autostock.common.util.SecretMasking;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *
 * <h2>JSON이 아닌 응답(2026-10-22 실측)</h2>
 * FRED가 간헐적으로 {@code 200 OK}에 {@code text/html} 본문을 돌려준다(10/22 06:19~13:05 매시 실패, 14:05 회복 —
 * 10/19~10/21에도 띄엄띄엄). 예전에는 WebClient가 Map으로 바꾸다 {@code UnsupportedMediaTypeException}을 던져 매시
 * ERROR와 스택이 남았다. 이제는 본문을 글자로 받아 JSON인지 보고, 아니면 {@value #RETRY_DELAY_SECONDS}초 뒤 한 번 더
 * 묻는다. 그래도 아니면 페이지 제목(또는 앞부분)을 담은 WARN 한 줄만 남긴다 — 다음 시간별 점검이 다시 받는다.
 */
@Component
public class FredClient {

    private static final Logger log = LoggerFactory.getLogger(FredClient.class);

    private static final String BASE_URL = "https://api.stlouisfed.org";

    /** VIX(변동성지수) series id. */
    public static final String SERIES_VIX = "VIXCLS";
    /** 달러인덱스(Trade Weighted U.S. Dollar Index: Broad, Goods and Services) series id. */
    public static final String SERIES_DXY = "DTWEXBGS";

    /** JSON이 아닌 응답이면 이만큼 기다렸다 한 번 더 묻는다(2026-10-22). */
    static final int RETRY_DELAY_SECONDS = 5;
    private static final int MAX_ATTEMPTS = 2;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final Pattern HTML_TITLE = Pattern.compile("<title[^>]*>(.*?)</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

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
        for (int attempt = 1; ; attempt++) {
            try {
                Map<String, Object> response = callApi(seriesId);
                return parse(seriesId, response);
            } catch (NonJsonResponseException e) {
                if (attempt < MAX_ATTEMPTS) {
                    log.info("FRED {}: JSON이 아닌 응답({}) — {}초 뒤 한 번 더 묻는다", seriesId, e.getMessage(),
                            RETRY_DELAY_SECONDS);
                    pause(Duration.ofSeconds(RETRY_DELAY_SECONDS));
                    continue;
                }
                log.warn("FRED 조회 실패(series={}) — JSON이 아닌 응답이 이어졌다({}). FRED 쪽 점검·과부하로 보인다 — "
                        + "다음 시간별 점검에서 다시 받는다", seriesId, e.getMessage());
                return Optional.empty();
            } catch (RuntimeException e) {
                // 망 오류·시간 초과·4xx/5xx — 시간별 점검이 다시 받는다. 원인을 보려고 스택은 남긴다(키는 가린다)
                log.warn("FRED 조회 실패(series={}) — 다음 시간별 점검에서 다시 받는다", seriesId,
                        SecretMasking.sanitizeForLogging(e));
                return Optional.empty();
            }
        }
    }

    /** 재시도 전 대기 — 시험이 오버라이드해 기다리지 않게 열어 둔다. */
    protected void pause(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 실제 HTTP 호출 지점 — protected로 열어 두어 테스트에서 이 메서드를 오버라이드해
     * 준비된 응답으로 대체할 수 있게 했다({@code market.HolidaySyncService.callApi}와 같은
     * 이 저장소의 기존 관례).
     */
    protected Map<String, Object> callApi(String seriesId) {
        return parseJson(fetchBody(seriesId));
    }

    /**
     * 응답 본문을 글자로 받는다 — 본문 형식(JSON·HTML)을 보고 다루려고 Map으로 바로 바꾸지 않는다(2026-10-22, 클래스 설명
     * "JSON이 아닌 응답"). 시험이 오버라이드할 수 있게 열어 둔다.
     */
    protected String fetchBody(String seriesId) {
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
                .bodyToMono(String.class)
                .block(); // 타임아웃은 공통 설정(spring.http.reactiveclient.*), 재시도는 스케줄 따라잡기가 대신한다
    }

    /** 본문이 JSON 객체면 Map으로, 아니면 {@link NonJsonResponseException}(페이지 제목 또는 앞부분을 담는다). */
    static Map<String, Object> parseJson(String body) {
        String trimmed = body == null ? "" : body.strip();
        if (!trimmed.startsWith("{")) {
            throw new NonJsonResponseException(describe(trimmed));
        }
        try {
            return JSON.readValue(trimmed, MAP_TYPE);
        } catch (IOException e) {
            throw new NonJsonResponseException("JSON 파싱 실패 — " + describe(trimmed));
        }
    }

    /** 로그용 요약 — HTML이면 제목, 아니면 앞 120자(공백 정리). */
    static String describe(String body) {
        if (body.isEmpty()) {
            return "빈 본문";
        }
        Matcher title = HTML_TITLE.matcher(body);
        if (title.find()) {
            return "HTML 제목 \"" + title.group(1).strip().replaceAll("\\s+", " ") + "\"";
        }
        String flat = body.replaceAll("\\s+", " ");
        return "본문 앞부분 \"" + (flat.length() > 120 ? flat.substring(0, 120) + "…" : flat) + "\"";
    }

    /** FRED가 JSON이 아닌 본문을 돌려줬다(2026-10-22 실측: 200 OK text/html). */
    static final class NonJsonResponseException extends RuntimeException {
        NonJsonResponseException(String message) {
            super(message);
        }
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
                // FRED 결측치 표기 '.'(휴장일) — 이번 수집은 스킵하고 다음 배치를 기다린다.
                // 실측 확정(2026-09-18, docs/measured/ext_probe_20260918_fred_*.json): observations[0] = 최신,
                // {"date":"2026-09-16","value":"17.71"} — 응답 지연 1~3영업일(DTWEXBGS는 주간 갱신).
                log.warn("FRED {} 최신 관측치가 결측('.') — 이번 수집 스킵", seriesId);
                return Optional.empty();
            }
            return Optional.of(new Observation(LocalDate.parse(dateStr), new BigDecimal(valueStr)));
        } catch (RuntimeException e) {
            log.error("FRED 응답 파싱 실패(series={}, keys={})", seriesId, response.keySet(), e);
            return Optional.empty();
        }
    }

    /** FRED 관측치 한 건. */
    public record Observation(LocalDate date, BigDecimal value) {
    }
}

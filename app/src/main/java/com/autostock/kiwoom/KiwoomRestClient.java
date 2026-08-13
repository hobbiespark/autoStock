package com.autostock.kiwoom;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;

/**
 * 키움 REST 호출의 <b>단일 진입점</b> — 모든 REST 호출은 반드시 이 클래스를 거친다.
 *
 * <p>왜 한 곳으로 모으는가? 키움 API 호출에는 매번 붙어야 하는 공통 규칙이 있다:
 * <pre>
 *   호출 요청
 *     │
 *     ▼
 *   ① TR별 rate limit 통과 대기   ← {@link TrRateLimiter} (사전 예방)
 *     │
 *     ▼
 *   ② 429 응답 시 백오프 재시도    ← 그래도 초과했다면 (사후 대응, 최대 4회)
 *     │
 *     ▼
 *   ③ 토큰 첨부 + api-id 헤더     ← {@link TokenManager}, {@link TrId}
 *     │
 *     ▼
 *   키움 서버
 * </pre>
 * 이 규칙을 흩어놓으면 하나라도 빠뜨린 호출이 사고를 낸다.
 * (예: api-id 오타 → 엉뚱한 TR 실행, PLAN 3절 사고 사례)
 *
 * <p>참고: 키움 REST는 <b>조회도 POST 방식</b>이다. HTTP 메서드가 아니라
 * api-id 헤더가 "무엇을 할지"를 결정한다 — 그래서 TrId enum 강제가 중요하다.
 */
@Component
public class KiwoomRestClient {

    private final WebClient webClient;
    private final TokenManager tokenManager;
    private final TrRateLimiter rateLimiter;
    private final MeterRegistry meterRegistry;

    /** 429(호출 한도 초과) 전용 재시도. 다른 오류는 재시도하지 않는다 — 주문 중복 위험 때문. */
    private final Retry retry;

    public KiwoomRestClient(WebClient.Builder builder,
                            KiwoomProperties properties,
                            TokenManager tokenManager,
                            TrRateLimiter rateLimiter,
                            MeterRegistry meterRegistry) {
        this.webClient = builder.baseUrl(properties.restBaseUrl()).build();
        this.tokenManager = tokenManager;
        this.rateLimiter = rateLimiter;
        this.meterRegistry = meterRegistry;
        this.retry = Retry.of("kiwoom-429", RetryConfig.custom()
                .maxAttempts(4)                          // 최초 1회 + 재시도 3회
                .waitDuration(Duration.ofMillis(600))    // 재시도 간격
                // 429만 재시도 대상: 타임아웃·5xx를 무턱대고 재시도하면
                // "주문이 실제로는 접수됐는데 또 보내는" 중복 사고가 날 수 있다
                .retryOnException(e -> e instanceof WebClientResponseException.TooManyRequests)
                .build());
    }

    /**
     * 키움 REST API를 호출한다.
     *
     * @param trId TR 식별자 — api-id 헤더로 전송되며, rate limit 버킷 키로도 쓰인다
     * @param path API 경로 (예: "/api/dostk/stkinfo")
     * @param body 요청 본문 (TR별 파라미터)
     * @return 응답 JSON을 Map으로 (타입 매핑은 각 서비스에서 TR별 DTO로 발전시킬 것)
     * @throws KiwoomApiException 4xx/5xx 오류 응답 시
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> call(TrId trId, String path, Map<String, Object> body) {
        // kiwoom.api.latency: TR(api_id)별 지연 분포를 태그로 나눠 기록한다. rate limiter 대기
        // 시간까지 포함해서 재는데(의도적) — "얼마나 기다렸는지"가 rate limit 튜닝의 원천이고,
        // 체결까지 걸린 총 시간은 슬리피지 분석의 기초 데이터이기 때문이다 (PLAN ADR-5).
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            // 바깥: rate limiter (통과할 때까지 대기) → 안쪽: 429 재시도 → 최심부: 실제 HTTP 호출
            return rateLimiter.execute(trId, () ->
                    Retry.decorateSupplier(retry, () -> {
                        Map<String, Object> response = (Map<String, Object>) webClient.post()
                                .uri(path)
                                .header("authorization", "Bearer " + tokenManager.accessToken())
                                .header("api-id", trId.apiId())
                                .contentType(MediaType.valueOf("application/json;charset=UTF-8"))
                                .bodyValue(body)
                                .retrieve()
                                // 오류 응답이면 본문을 읽어 예외 메시지에 포함 — 디버깅 편의
                                .onStatus(HttpStatusCode::isError, resp ->
                                        resp.bodyToMono(String.class).map(msg ->
                                                new KiwoomApiException("키움 API 오류 [" + trId.apiId() + "] " + msg)))
                                .bodyToMono(Map.class)
                                .block();
                        return checkReturnCode(trId, response);
                    }).get());
        } finally {
            sample.stop(Timer.builder("kiwoom.api.latency")
                    .description("TR(api_id)별 키움 REST API 호출 지연(rate limit 대기 포함)")
                    .tag("api_id", trId.apiId())
                    .register(meterRegistry));
        }
    }

    /**
     * HTTP 200이어도 키움 응답 본문의 {@code return_code}가 0이 아니면 논리 오류다
     * (예: 파라미터 오류, 권한 없음 등). 실측(kt00018/ka10081)상 정상 응답도 이 필드를
     * 포함하므로, 있으면 항상 검사한다 — 없는 TR도 있을 수 있어 필드 부재는 통과시킨다.
     */
    private Map<String, Object> checkReturnCode(TrId trId, Map<String, Object> response) {
        if (response == null) {
            return null;
        }
        Object returnCode = response.get("return_code");
        if (returnCode != null && ((Number) returnCode).intValue() != 0) {
            throw new KiwoomApiException(
                    "키움 API 논리 오류 [" + trId.apiId() + "] " + response.get("return_msg"));
        }
        return response;
    }
}

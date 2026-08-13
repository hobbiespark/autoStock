package com.autostock.kiwoom;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;

/**
 * 키움 REST 호출 단일 진입점.
 * 모든 호출은 TR enum + TR별 rate limiter + 429 백오프 retry를 강제 통과.
 */
@Component
public class KiwoomRestClient {

    private final WebClient webClient;
    private final TokenManager tokenManager;
    private final TrRateLimiter rateLimiter;
    private final Retry retry;

    public KiwoomRestClient(WebClient.Builder builder,
                            KiwoomProperties properties,
                            TokenManager tokenManager,
                            TrRateLimiter rateLimiter) {
        this.webClient = builder.baseUrl(properties.restBaseUrl()).build();
        this.tokenManager = tokenManager;
        this.rateLimiter = rateLimiter;
        this.retry = Retry.of("kiwoom-429", RetryConfig.custom()
                .maxAttempts(4)
                .waitDuration(Duration.ofMillis(600))   // 지수 백오프 대용 초기값
                .retryOnException(e -> e instanceof WebClientResponseException.TooManyRequests)
                .build());
    }

    /** POST 호출 (키움 REST는 조회도 POST + api-id 헤더 방식). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> call(TrId trId, String path, Map<String, Object> body) {
        return rateLimiter.execute(trId, () ->
                Retry.decorateSupplier(retry, () -> (Map<String, Object>) webClient.post()
                        .uri(path)
                        .header("authorization", "Bearer " + tokenManager.accessToken())
                        .header("api-id", trId.apiId())
                        .bodyValue(body)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, resp ->
                                resp.bodyToMono(String.class).map(msg ->
                                        new KiwoomApiException("키움 API 오류 [" + trId.apiId() + "] " + msg)))
                        .bodyToMono(Map.class)
                        .block()
                ).get());
    }
}

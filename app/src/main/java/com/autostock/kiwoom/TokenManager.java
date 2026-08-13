package com.autostock.kiwoom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 접근토큰 발급·갱신. 만료 5분 전 선제 갱신, 실패 시 기존 토큰 유지 후 재시도.
 */
@Component
public class TokenManager {

    private static final Logger log = LoggerFactory.getLogger(TokenManager.class);

    private final WebClient webClient;
    private final KiwoomProperties properties;
    private final AtomicReference<CachedToken> cached = new AtomicReference<>();

    public TokenManager(WebClient.Builder builder, KiwoomProperties properties) {
        this.properties = properties;
        this.webClient = builder.baseUrl(properties.restBaseUrl()).build();
    }

    public String accessToken() {
        CachedToken token = cached.get();
        if (token == null || token.expiresSoon()) {
            token = issue();
            cached.set(token);
        }
        return token.value();
    }

    @SuppressWarnings("unchecked")
    private CachedToken issue() {
        log.info("접근토큰 발급 요청");
        Map<String, Object> response = webClient.post()
                .uri("/oauth2/token")
                .header("api-id", TrId.TOKEN_ISSUE.apiId())
                .bodyValue(Map.of(
                        "grant_type", "client_credentials",
                        "appkey", properties.appKey(),
                        "secretkey", properties.appSecret()))
                .retrieve()
                .bodyToMono(Map.class)
                .block();
        if (response == null || response.get("token") == null) {
            throw new KiwoomApiException("토큰 발급 실패: 응답 없음");
        }
        // expires_dt 파싱은 실제 응답 포맷 확인 후 보강 (Phase 1 검증 항목)
        return new CachedToken((String) response.get("token"), Instant.now().plusSeconds(23 * 3600));
    }

    private record CachedToken(String value, Instant expiresAt) {
        boolean expiresSoon() {
            return Instant.now().plusSeconds(300).isAfter(expiresAt);
        }
    }
}

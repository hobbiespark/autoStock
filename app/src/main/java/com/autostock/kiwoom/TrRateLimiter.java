package com.autostock.kiwoom;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * TR(api-id)별 독립 rate limiter — 커뮤니티 검증값: 약 1req/s, 버스트 2 (PLAN 3절).
 * 429 응답 백오프는 KiwoomRestClient의 retry에서 처리.
 */
@Component
public class TrRateLimiter {

    private final RateLimiterRegistry registry;

    public TrRateLimiter() {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(2)                                // 버스트 2
                .limitRefreshPeriod(Duration.ofSeconds(1))        // 초당 갱신
                .timeoutDuration(Duration.ofSeconds(10))          // 슬롯 대기 상한
                .build();
        this.registry = RateLimiterRegistry.of(config);
    }

    /** TR별 독립 리미터를 통과시켜 실행한다. */
    public <T> T execute(TrId trId, Supplier<T> call) {
        RateLimiter limiter = registry.rateLimiter(trId.apiId());
        return RateLimiter.decorateSupplier(limiter, call).get();
    }
}

package com.autostock.kiwoom;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * TR별 호출 속도 제한기 — 키움 서버로부터 차단당하지 않기 위한 브레이크.
 *
 * <p>배경: 키움 REST API는 TR(api-id) <b>각각에 대해 독립적으로</b> 호출 횟수를 제한한다.
 * 예를 들어 "현재가 조회"를 초당 10번 부르면 429(Too Many Requests)가 떨어지고,
 * 심하면 일시 차단될 수 있다. 커뮤니티에서 검증된 안전값은 TR당 초당 1건. (PLAN 3절)
 * <b>실측(2026-09-23)</b>: 버스트 2로 두었더니 ka10081(일봉)·ka10080(분봉)이 HTTP 200 + return_code 5
 * {@code [1700:허용된 API 요청 개수를 초과하였습니다. 유량=1]}로 거부됐고(429가 아니라 재시도도 안 걸림),
 * C3가 005930·035420 판단을 건너뛰었다. 서버 유량이 TR당 초당 1건이므로 버킷도 1건으로 맞춘다.
 *
 * <p>동작 원리 — 토큰 버킷(token bucket)을 TR별로 하나씩:
 * <pre>
 *   [현재가 조회 버킷: ●]   [주문 버킷: ●]   [잔고 버킷: ●]   ← 서로 독립!
 *
 *   · 호출 1건 = 토큰 1개 소비
 *   · 1초마다 토큰이 1개 다시 채워짐 (= 서버 유량 1건/초)
 *   · 토큰이 없으면? 예외를 던지지 않고 최대 10초까지 줄 서서 기다림
 *     (자동매매에서는 "빨리 실패"보다 "조금 늦게라도 성공"이 대체로 낫다)
 * </pre>
 *
 * <p>이 클래스는 사전 예방이고, 그래도 429 또는 return_code 5(1700 유량 초과)가 오면
 * {@link KiwoomRestClient}의 재시도(백오프)가 사후 대응을 맡는다. 2중 방어.
 */
@Component
public class TrRateLimiter {

    private final RateLimiterRegistry registry;

    public TrRateLimiter() {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(1)                                // 한 주기(1초)에 허용할 호출 수 — 서버 유량 1건/초(실측 1700 오류)
                .limitRefreshPeriod(Duration.ofSeconds(1))        // 토큰 리필 주기
                .timeoutDuration(Duration.ofSeconds(10))          // 토큰 대기 상한 — 초과 시 RequestNotPermitted 예외
                .build();
        this.registry = RateLimiterRegistry.of(config);
    }

    /**
     * 주어진 호출을 해당 TR의 리미터를 통과시켜 실행한다.
     *
     * @param trId TR 식별자 — 리미터는 TR마다 자동 생성·재사용된다
     * @param call 실제 API 호출 (리미터 통과 후에만 실행됨)
     */
    public <T> T execute(TrId trId, Supplier<T> call) {
        // registry.rateLimiter(이름): 같은 이름이면 기존 리미터 재사용, 처음이면 생성
        RateLimiter limiter = registry.rateLimiter(trId.apiId());
        return RateLimiter.decorateSupplier(limiter, call).get();
    }
}

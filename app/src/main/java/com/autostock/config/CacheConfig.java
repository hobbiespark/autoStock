package com.autostock.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Caffeine 로컬 캐시 설정 (PLAN ADR-5).
 *
 * <p>왜 Redis가 아니라 Caffeine인가 — 지금은 단일 JVM 모놀리스(PLAN ADR-1)라서 Redis를
 * 추가하면 네트워크 홉·운영 부담만 늘어난다. 물리 분리 시점에 Caffeine(L1)+Redis(L2)
 * 계층화로 확장할 수 있다.
 *
 * <p>캐시별로 TTL·용량 정책이 다르므로(현재가는 초 단위로 갱신, 일봉은 하루 한 번이면 충분)
 * 캐시마다 별도 Caffeine 인스턴스를 만든다. {@code recordStats()}로 히트율 등 통계를
 * 수집하면, Spring Boot Actuator가 이를 자동으로 Micrometer 게이지로 노출한다
 * ({@code cache.gets}, {@code cache.puts} 등 — {@code /actuator/metrics} 참고).
 *
 * <p><b>주의: 주문·잔고(계좌 상태)는 절대 캐시하지 않는다.</b> 캐시된 낡은 잔고/미체결
 * 정보로 주문 판단을 내리면 실제 계좌 상태와 어긋나는 사고로 이어질 수 있다.
 * 여기 등록하는 캐시는 시세 조회(현재가·일봉)로 한정한다.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** 현재가 캐시 이름 — MarketQueryService#stockPrice에서 참조. */
    public static final String STOCK_PRICE_CACHE = "stockPrice";

    /** 일봉 캐시 이름 — MarketQueryService#dailyChart에서 참조. */
    public static final String DAILY_CHART_CACHE = "dailyChart";

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(
                // 현재가: TTL 1초 — 장중 시세는 초 단위로도 바뀌므로 아주 짧게만 재사용한다.
                buildCache(STOCK_PRICE_CACHE, 1, TimeUnit.SECONDS, 500),
                // 일봉: TTL 1시간 — 당일 봉은 장중에 계속 바뀌지만, 과거 확정봉 재조회가
                // 대부분이라 1시간 정도는 재사용해도 무방하다.
                buildCache(DAILY_CHART_CACHE, 1, TimeUnit.HOURS, 100)
        ));
        return manager;
    }

    private CaffeineCache buildCache(String name, long ttl, TimeUnit unit, int maxSize) {
        return new CaffeineCache(name, Caffeine.newBuilder()
                .expireAfterWrite(ttl, unit)
                .maximumSize(maxSize)
                .recordStats()          // 히트율을 Micrometer에 노출(목표 >90%, PLAN ADR-5)
                .build());
    }
}

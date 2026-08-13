package com.autostock.marketdata;

import com.autostock.config.CacheConfig;
import com.autostock.kiwoom.KiwoomRestClient;
import com.autostock.kiwoom.TrId;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Phase 1 검증용 REST 조회. WS 실시간은 Phase 2에서 추가.
 *
 * <p>조회 결과는 Caffeine 로컬 캐시로 재사용한다(PLAN ADR-5, CacheConfig 참고) — TR별
 * rate limit이 초당 1건 수준이라 같은 값을 반복 조회하는 비용을 줄이는 효과가 크다.
 * <b>주의: 이 클래스에는 절대 주문·잔고 조회를 추가하지 말 것.</b> 캐시된 낡은 잔고로
 * 리스크 판단을 하면 실제 계좌 상태와 어긋나는 사고로 이어진다 — 주문·잔고는
 * execution/AccountService처럼 캐시 없는 별도 경로를 쓴다.
 */
@Service
public class MarketQueryService {

    private final KiwoomRestClient client;

    public MarketQueryService(KiwoomRestClient client) {
        this.client = client;
    }

    /** 현재가/기본정보 조회. TTL 1초 캐시 — 장중 시세는 초 단위로 바뀌므로 짧게만 재사용한다. */
    @Cacheable(cacheNames = CacheConfig.STOCK_PRICE_CACHE, key = "#symbol")
    public Map<String, Object> stockPrice(String symbol) {
        return client.call(TrId.STOCK_PRICE, "/api/dostk/stkinfo", Map.of("stk_cd", symbol));
    }

    /** 일봉 조회. TTL 1시간 캐시 — 과거 확정봉 재조회가 대부분이라 오래 재사용해도 무방하다. */
    @Cacheable(cacheNames = CacheConfig.DAILY_CHART_CACHE, key = "#symbol + '-' + #baseDate")
    public Map<String, Object> dailyChart(String symbol, String baseDate) {
        return client.call(TrId.DAILY_CHART, "/api/dostk/chart",
                Map.of("stk_cd", symbol, "base_dt", baseDate, "upd_stkpc_tp", "1"));
    }
}

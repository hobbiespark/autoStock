package com.autostock.marketdata;

import com.autostock.kiwoom.KiwoomRestClient;
import com.autostock.kiwoom.TrId;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Phase 1 검증용 REST 조회. WS 실시간은 Phase 2에서 추가.
 */
@Service
public class MarketQueryService {

    private final KiwoomRestClient client;

    public MarketQueryService(KiwoomRestClient client) {
        this.client = client;
    }

    /** 현재가/기본정보 조회 */
    public Map<String, Object> stockPrice(String symbol) {
        return client.call(TrId.STOCK_PRICE, "/api/dostk/stkinfo", Map.of("stk_cd", symbol));
    }

    /** 일봉 조회 */
    public Map<String, Object> dailyChart(String symbol, String baseDate) {
        return client.call(TrId.DAILY_CHART, "/api/dostk/chart",
                Map.of("stk_cd", symbol, "base_dt", baseDate, "upd_stkpc_tp", "1"));
    }
}

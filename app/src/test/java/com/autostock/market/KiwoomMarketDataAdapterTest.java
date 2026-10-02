package com.autostock.market;

import com.autostock.common.event.Candle;
import com.autostock.common.util.StockNames;
import com.autostock.kiwoom.KiwoomRestClient;
import com.autostock.kiwoom.TrId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * KiwoomMarketDataAdapter — 키움 TR 응답(Map·필드명)을 market 모델로 번역하는 경계(ACL).
 * 요청 계약(TR·경로·파라미터)과 필드 파싱을 고정한다. 응답 형태는 docs/measured 실측 기준.
 */
class KiwoomMarketDataAdapterTest {

    private final KiwoomRestClient client = mock(KiwoomRestClient.class);
    private final KiwoomMarketDataAdapter adapter = new KiwoomMarketDataAdapter(client);

    @Test
    void 기본정보는_부호를_벗긴_가격과_종목명으로_번역한다() {
        when(client.call(TrId.STOCK_PRICE, "/api/dostk/stkinfo", Map.of("stk_cd", "005930")))
                .thenReturn(Map.of("stk_nm", "삼성전자", "cur_prc", "-258000",
                        "open_pric", "+261000", "high_pric", "+261000", "low_pric", "+257500"));

        MarketDataPort.StockQuote quote = adapter.stockQuote("005930");

        assertEquals("삼성전자", quote.name());
        assertEquals("삼성전자(005930)", StockNames.label("005930")); // ka10001을 부를 때마다 사전에 남긴다
        assertEquals(new BigDecimal("258000"), quote.current());
        assertEquals(new BigDecimal("261000"), quote.open());
        assertEquals(new BigDecimal("261000"), quote.high());
        assertEquals(new BigDecimal("257500"), quote.low());
    }

    @Test
    void 기본정보의_빈값과_0은_null이다() {
        Map<String, Object> response = new HashMap<>();
        response.put("stk_nm", " ");
        response.put("cur_prc", "0");
        response.put("open_pric", "");
        when(client.call(TrId.STOCK_PRICE, "/api/dostk/stkinfo", Map.of("stk_cd", "005930"))).thenReturn(response);

        MarketDataPort.StockQuote quote = adapter.stockQuote("005930");

        assertNull(quote.name());
        assertFalse(StockNames.isKnown("005930")); // 빈 이름은 사전에 남기지 않는다
        assertNull(quote.current());
        assertNull(quote.open());
        assertNull(quote.high());
    }

    @Test
    void 호가는_최우선_매도_매수호가로_번역한다() {
        when(client.call(TrId.STOCK_ORDERBOOK, "/api/dostk/mrkcond", Map.of("stk_cd", "005930")))
                .thenReturn(Map.of("sel_fpr_bid", "+259000", "buy_fpr_bid", "-258500", "bid_req_base_tm", "112046"));

        MarketDataPort.BestQuote best = adapter.bestQuote("005930");

        assertEquals(new BigDecimal("259000"), best.bestAsk());
        assertEquals(new BigDecimal("258500"), best.bestBid());
    }

    @Test
    void 일봉은_최신순_응답을_날짜_오름차순_캔들로_번역한다() {
        when(client.call(TrId.DAILY_CHART, "/api/dostk/chart",
                Map.of("stk_cd", "005930", "base_dt", "20260813", "upd_stkpc_tp", "1")))
                .thenReturn(Map.of("stk_cd", "005930", "stk_dt_pole_chart_qry", List.of(
                        Map.of("dt", "20260813", "open_pric", "267500", "high_pric", "270000",
                                "low_pric", "262500", "cur_prc", "269000", "trde_qty", "13065302"),
                        Map.of("dt", "20260812", "open_pric", "260000", "high_pric", "262000",
                                "low_pric", "259000", "cur_prc", "261000", "trde_qty", "9000000"))));

        List<Candle> candles = adapter.dailyCandles("005930", LocalDate.of(2026, 8, 13));

        assertEquals(2, candles.size());
        assertEquals(LocalDate.of(2026, 8, 12), candles.get(0).date());
        Candle last = candles.get(1);
        assertEquals("005930", last.symbol());
        assertEquals(LocalDate.of(2026, 8, 13), last.date());
        assertEquals(0, new BigDecimal("267500").compareTo(last.open()));
        assertEquals(0, new BigDecimal("269000").compareTo(last.close()));
        assertEquals(13065302L, last.volume());
    }

    @Test
    void 일봉_배열이_없으면_빈_목록이다() {
        when(client.call(TrId.DAILY_CHART, "/api/dostk/chart",
                Map.of("stk_cd", "005930", "base_dt", "20260813", "upd_stkpc_tp", "1")))
                .thenReturn(Map.of("return_code", 0));

        assertTrue(adapter.dailyCandles("005930", LocalDate.of(2026, 8, 13)).isEmpty());
    }

    @Test
    void 분봉은_체결시각과_부호를_벗긴_값으로_번역하고_해석할_수_없는_행은_건너뛴다() {
        when(client.call(TrId.MINUTE_CHART, "/api/dostk/chart",
                Map.of("stk_cd", "005930", "tic_scope", "1", "upd_stkpc_tp", "1")))
                .thenReturn(Map.of("stk_min_pole_chart_qry", List.of(
                        Map.of("cntr_tm", "20260930090100", "open_pric", "+258000", "high_pric", "+258500",
                                "low_pric", "-257500", "cur_prc", "+258000", "trde_qty", "1200", "acc_trde_qty", "3400"),
                        Map.of("cntr_tm", "", "cur_prc", "1"),
                        "not-a-row")));

        List<MarketDataPort.MinuteBar> bars = adapter.minuteBars("005930");

        assertEquals(1, bars.size());
        MarketDataPort.MinuteBar bar = bars.get(0);
        assertEquals(LocalDateTime.of(2026, 9, 30, 9, 1, 0), bar.time());
        assertEquals(new BigDecimal("257500"), bar.low());
        assertEquals(new BigDecimal("258000"), bar.close());
        assertEquals(1200L, bar.volume());
        assertEquals(3400L, bar.accumulatedVolume());
    }
}

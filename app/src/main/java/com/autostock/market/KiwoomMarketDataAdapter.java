package com.autostock.market;

import com.autostock.common.event.Candle;
import com.autostock.common.util.KiwoomNumbers;
import com.autostock.config.CacheConfig;
import com.autostock.kiwoom.KiwoomRestClient;
import com.autostock.kiwoom.TrId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 키움 REST 시세 TR을 {@link MarketDataPort} 모델로 번역하는 어댑터(ACL). 키움 필드명은 이 클래스에만 둔다.
 *
 * <p>조회 결과는 Caffeine 로컬 캐시로 재사용한다(PLAN ADR-5, CacheConfig) — TR별 rate limit이 초당 1건
 * 수준이라 반복 조회 비용을 줄인다. <b>주문·잔고 조회를 여기에 추가하지 않는다</b> — 캐시된 낡은 잔고로
 * 리스크를 판단하면 실제 계좌와 어긋난다(주문·잔고는 execution.BrokerPort).
 *
 * <p>응답 필드(실측): ka10001 {@code stk_nm, cur_prc, open_pric, high_pric, low_pric}
 * (docs/measured/tr_probe_20260918_ka10001.json), ka10004 {@code sel_fpr_bid, buy_fpr_bid}
 * (…_ka10004.json), ka10081 {@code stk_dt_pole_chart_qry[] dt/open_pric/…/trde_qty} 최신일부터 내림차순
 * (2026-08-13), ka10080 {@code stk_min_pole_chart_qry[] cntr_tm(yyyyMMddHHmmss)/…/acc_trde_qty} (2026-09-11).
 * 가격류는 전일대비 부호가 접두로 붙을 수 있다.
 */
@Service
public class KiwoomMarketDataAdapter implements MarketDataPort {

    private static final Logger log = LoggerFactory.getLogger(KiwoomMarketDataAdapter.class);

    private static final DateTimeFormatter BASE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MINUTE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String DAILY_FIELD = "stk_dt_pole_chart_qry";
    private static final String MINUTE_FIELD = "stk_min_pole_chart_qry";

    private final KiwoomRestClient client;

    public KiwoomMarketDataAdapter(KiwoomRestClient client) {
        this.client = client;
    }

    /** TTL 1초 캐시 — 장중 시세는 초 단위로 바뀐다. */
    @Override
    @Cacheable(cacheNames = CacheConfig.STOCK_PRICE_CACHE, key = "#symbol")
    public StockQuote stockQuote(String symbol) {
        Map<String, Object> r = client.call(TrId.STOCK_PRICE, "/api/dostk/stkinfo", Map.of("stk_cd", symbol));
        return new StockQuote(text(r, "stk_nm"), price(r, "cur_prc"), price(r, "open_pric"),
                price(r, "high_pric"), price(r, "low_pric"));
    }

    /** TTL 1초 캐시(현재가와 동일 정책). */
    @Override
    @Cacheable(cacheNames = CacheConfig.ORDERBOOK_CACHE, key = "#symbol")
    public BestQuote bestQuote(String symbol) {
        Map<String, Object> r = client.call(TrId.STOCK_ORDERBOOK, "/api/dostk/mrkcond", Map.of("stk_cd", symbol));
        return new BestQuote(price(r, "sel_fpr_bid"), price(r, "buy_fpr_bid"));
    }

    /** TTL 1시간 캐시 — 과거 확정봉 재조회가 대부분이다. */
    @Override
    @Cacheable(cacheNames = CacheConfig.DAILY_CHART_CACHE, key = "#symbol + '-' + #baseDate")
    public List<Candle> dailyCandles(String symbol, LocalDate baseDate) {
        Map<String, Object> r = client.call(TrId.DAILY_CHART, "/api/dostk/chart",
                Map.of("stk_cd", symbol, "base_dt", baseDate.format(BASE_DATE), "upd_stkpc_tp", "1"));
        if (!(r.get(DAILY_FIELD) instanceof List<?> rows) || rows.isEmpty()) {
            log.warn("일봉 응답에 {} 필드가 없거나 비어 있음 (symbol={}, baseDate={})", DAILY_FIELD, symbol, baseDate);
            return List.of();
        }
        List<Candle> candles = new ArrayList<>(rows.size());
        for (Object row : rows) {
            if (row instanceof Map<?, ?> bar) {
                candles.add(new Candle(symbol,
                        LocalDate.parse(String.valueOf(bar.get("dt")), BASE_DATE),
                        KiwoomNumbers.toBigDecimal(bar.get("open_pric")),
                        KiwoomNumbers.toBigDecimal(bar.get("high_pric")),
                        KiwoomNumbers.toBigDecimal(bar.get("low_pric")),
                        KiwoomNumbers.toBigDecimal(bar.get("cur_prc")),
                        KiwoomNumbers.toLong(bar.get("trde_qty"))));
            }
        }
        Collections.reverse(candles); // 응답은 최신일부터 — 오름차순으로 뒤집는다
        return candles;
    }

    @Override
    public List<MinuteBar> minuteBars(String symbol) {
        Map<String, Object> r = client.call(TrId.MINUTE_CHART, "/api/dostk/chart",
                Map.of("stk_cd", symbol, "tic_scope", "1", "upd_stkpc_tp", "1"));
        if (!(r.get(MINUTE_FIELD) instanceof List<?> rows)) {
            log.warn("분봉 응답에 배열 없음({}): 키={}", symbol, r.keySet());
            return List.of();
        }
        List<MinuteBar> bars = new ArrayList<>(rows.size());
        for (Object row : rows) {
            if (row instanceof Map<?, ?> bar) {
                LocalDateTime time = parseMinuteTime(bar.get("cntr_tm"));
                if (time != null) {
                    bars.add(new MinuteBar(time,
                            KiwoomNumbers.toBigDecimal(bar.get("open_pric")).abs(),
                            KiwoomNumbers.toBigDecimal(bar.get("high_pric")).abs(),
                            KiwoomNumbers.toBigDecimal(bar.get("low_pric")).abs(),
                            KiwoomNumbers.toBigDecimal(bar.get("cur_prc")).abs(),
                            KiwoomNumbers.toLongOrZero(bar.get("trde_qty")),
                            KiwoomNumbers.toLongOrZero(bar.get("acc_trde_qty"))));
                }
            }
        }
        return bars;
    }

    private static LocalDateTime parseMinuteTime(Object raw) {
        try {
            return raw == null ? null : LocalDateTime.parse(String.valueOf(raw), MINUTE_TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 부호를 벗긴 양수 가격. 없거나 0이면 null. */
    private static BigDecimal price(Map<String, Object> map, String key) {
        Object raw = map.get(key);
        if (raw == null || String.valueOf(raw).isBlank()) {
            return null;
        }
        BigDecimal value = KiwoomNumbers.toBigDecimal(raw).abs();
        return value.signum() > 0 ? value : null;
    }

    private static String text(Map<String, Object> map, String key) {
        Object raw = map.get(key);
        return raw == null || String.valueOf(raw).isBlank() ? null : String.valueOf(raw).trim();
    }
}

package com.autostock.common.event;

import com.autostock.common.util.Price;
import com.autostock.common.util.StockCode;

import java.time.Instant;

/**
 * 정규화된 시세 이벤트. (스키마 v1)
 * source: LIVE(키움 WS) / REPLAY(백테스트) — 소비자는 구분하지 않는다.
 * symbol은 {@link StockCode}다. JSON에서는 기존과 같은 문자열이다(JacksonConfig).
 * price는 {@link Price}다(항상 양수). JSON에서는 기존과 같은 숫자다. 가격이 0이거나 없는 시세는 파서가 버린다.
 */
public record MarketTick(
        StockCode symbol,
        Price price,
        long volume,
        Instant timestamp,
        Source source
) {
    public enum Source { LIVE, REPLAY }
}

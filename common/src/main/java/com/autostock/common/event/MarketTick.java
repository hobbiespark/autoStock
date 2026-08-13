package com.autostock.common.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 정규화된 시세 이벤트. (스키마 v1)
 * source: LIVE(키움 WS) / REPLAY(백테스트) — 소비자는 구분하지 않는다.
 */
public record MarketTick(
        String symbol,
        BigDecimal price,
        long volume,
        Instant timestamp,
        Source source
) {
    public enum Source { LIVE, REPLAY }
}

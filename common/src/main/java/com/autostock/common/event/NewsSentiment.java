package com.autostock.common.event;

import java.time.Instant;

/**
 * 뉴스 감성 이벤트. (스키마 v1)
 * score: -1.0(부정) ~ +1.0(긍정), KR-FinBERT 사이드카 산출.
 * 단독 매매 근거 금지 — strategy/risk에서 필터로만 소비 (PLAN 5절).
 */
public record NewsSentiment(
        String symbol,
        double score,
        String headline,
        String sourceUrl,
        Instant timestamp
) {
}

package com.autostock.common.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 거시 지표 이벤트. (스키마 v1)
 * indicatorId 예: ECOS_BASE_RATE, FRED_VIX, USDKRW, DART_DISCLOSURE
 */
public record MacroIndicator(
        String indicatorId,
        String scope,          // MARKET 전체 or 종목코드
        BigDecimal value,
        String detail,         // 공시 유형 등 부가정보
        Instant timestamp
) {
}

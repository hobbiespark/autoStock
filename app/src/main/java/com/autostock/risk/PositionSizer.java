package com.autostock.risk;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 고정비율(fixed fractional) 사이징 — PLAN 2절 (3).
 * 거래 50~100건 축적 후 fractional Kelly 전환 검토 (별도 구현체로 교체).
 */
@Component
public class PositionSizer {

    private final RiskProperties properties;

    public PositionSizer(RiskProperties properties) {
        this.properties = properties;
    }

    /**
     * @param equity 계좌 평가액 (KRW)
     * @param price  기준가
     * @return 매수 수량 (0이면 주문 불가)
     */
    public long sizeBuy(BigDecimal equity, BigDecimal price) {
        if (price == null || price.signum() <= 0 || equity == null || equity.signum() <= 0) {
            return 0;
        }
        BigDecimal budget = equity.multiply(BigDecimal.valueOf(properties.maxPositionPctPerSymbol()));
        return budget.divide(price, 0, RoundingMode.DOWN).longValue();
    }
}

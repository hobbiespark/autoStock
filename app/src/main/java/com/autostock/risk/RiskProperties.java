package com.autostock.risk;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 리스크 한도 (PLAN 8절 초기값). 코드가 아닌 설정으로 관리.
 */
@ConfigurationProperties(prefix = "risk")
public record RiskProperties(
        double maxPositionPctPerSymbol,   // 종목당 최대 계좌 비중 (기본 0.10)
        int maxConcurrentPositions,       // 동시 보유 종목 수 (기본 5)
        double stopLossPct,               // 손절 (기본 -0.03)
        double takeProfitPct,             // 익절 (기본 +0.05)
        double dailyMaxLossPct,           // 일 최대 손실 (기본 -0.02)
        int dailyMaxOrders                // 일 주문 횟수 상한
) {
}

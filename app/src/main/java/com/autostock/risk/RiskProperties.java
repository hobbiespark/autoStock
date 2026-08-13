package com.autostock.risk;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 리스크 한도 (PLAN 8절 초기값). 코드가 아닌 설정으로 관리.
 *
 * <p>feeRate/sellTaxRate는 {@code backtest.CostModel}의 기본값(매수/매도 수수료 각 0.015%,
 * 매도 거래세 0.15%)과 동일한 값이다 — 다만 여기서는 "체결가 그대로 왔다고 가정한 뒤
 * 수수료·세금만 뺀 실현손익 추정"에 쓰인다({@link DailyPnlTracker}). 슬리피지는 이미
 * fillPrice(체결가)에 반영되어 있으므로 별도로 다루지 않는다.
 */
@ConfigurationProperties(prefix = "risk")
public record RiskProperties(
        double maxPositionPctPerSymbol,   // 종목당 최대 계좌 비중 (기본 0.10)
        int maxConcurrentPositions,       // 동시 보유 종목 수 (기본 5)
        double stopLossPct,               // 손절 (기본 -0.03)
        double takeProfitPct,             // 익절 (기본 +0.05)
        double dailyMaxLossPct,           // 일 최대 손실 (기본 -0.02) — DailyPnlTracker가 이 한도로 킬스위치를 켠다
        int dailyMaxOrders,               // 일 주문 횟수 상한
        double paperEquity,               // 모의/시뮬레이션 계좌 평가액 (KRW) — PaperEquitySource, LIVE 폴백에도 쓰인다
        double feeRate,                   // 매수/매도 수수료율 — 실현손익 추정용(CostModel과 동일값, 기본 0.00015)
        double sellTaxRate,                // 매도 증권거래세율 — 실현손익 추정용(CostModel과 동일값, 기본 0.0015)
        boolean enforceMarketHours        // 장 시간(09:00~15:30) 외 Signal 거부 여부 (기본 true)
) {
}

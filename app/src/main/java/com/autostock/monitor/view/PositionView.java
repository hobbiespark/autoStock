package com.autostock.monitor.view;

import java.math.BigDecimal;

/**
 * 포지션 View DTO — {@code portfolio.PositionBook.Position}(ADR-6 재편으로 risk →
 * portfolio 이동)을 화면용으로 옮겨 담은 것.
 * FE는 Domain Entity를 직접 보지 않는다(ARCHITECTURE.md 13절 설계 규칙 13).
 *
 * @param symbol     종목코드
 * @param symbolName 종목명 — 화면은 코드와 함께 표시한다(2026-10-02). 아직 모르면 null
 * @param quantity   보유 수량
 * @param avgPrice   평균 매수 단가
 * @param currentPrice  현재가(키움 잔고 cur_prc, 30초마다 갱신) — 모르면 null(2026-10-23)
 * @param evaluationPnl 평가손익(원) = (현재가 − 평단) × 수량, 비용 전 — 모르면 null
 * @param returnPct     수익률(%) = 현재가 ÷ 평단 − 1, 소수 둘째 자리 — 모르면 null
 */
public record PositionView(String symbol, String symbolName, long quantity, BigDecimal avgPrice,
                           BigDecimal currentPrice, BigDecimal evaluationPnl, BigDecimal returnPct) {

    /** 현재가를 모르는 보유. */
    public PositionView(String symbol, String symbolName, long quantity, BigDecimal avgPrice) {
        this(symbol, symbolName, quantity, avgPrice, null, null, null);
    }
}

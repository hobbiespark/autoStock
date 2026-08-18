package com.autostock.monitor.view;

import java.math.BigDecimal;

/**
 * 포지션 View DTO — {@code portfolio.PositionBook.Position}(ADR-6 재편으로 risk →
 * portfolio 이동)을 화면용으로 옮겨 담은 것.
 * FE는 Domain Entity를 직접 보지 않는다(ARCHITECTURE.md 13절 설계 규칙 13).
 *
 * @param symbol   종목코드
 * @param quantity 보유 수량
 * @param avgPrice 평균 매수 단가
 */
public record PositionView(String symbol, long quantity, BigDecimal avgPrice) {
}

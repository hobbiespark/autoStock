package com.autostock.monitor.view;

import java.util.List;

/**
 * 주문 이력 화면 View DTO — {@code GET /api/orders}의 응답(FE-1, PLAN.md ADR-10 확장표).
 *
 * @param orders 최신순으로 정렬된 주문 목록(기간 필터 적용됨)
 */
public record OrderHistoryView(List<OrderHistoryItemView> orders) {
}

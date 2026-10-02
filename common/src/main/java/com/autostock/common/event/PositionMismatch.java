package com.autostock.common.event;

import com.autostock.common.util.StockCode;

import java.time.Instant;

/**
 * 잔고·장부 수량 불일치 이벤트. (스키마 v1 — 2026-10-02 신규, 실행 계획 1.5)
 *
 * <p>trading 모듈의 PositionReconciler가 브로커 잔고(kt00018)와 포지션 장부(PositionBook)의 같은 불일치를
 * 연속 2회(약 10분) 보면 한 번 발행한다. monitor 모듈이 받아 텔레그램 WARN으로 알린다. 자동 교정은 하지 않는다 —
 * 어느 쪽이 맞는지는 사람이 확인한다.
 *
 * @param symbol         종목
 * @param brokerQuantity 브로커 잔고 수량(없으면 0)
 * @param bookQuantity   장부 수량(없으면 0)
 * @param timestamp      판정 시각
 */
public record PositionMismatch(
        StockCode symbol,
        long brokerQuantity,
        long bookQuantity,
        Instant timestamp
) {
}

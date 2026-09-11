package com.autostock.common.event;

import java.math.BigDecimal;

/**
 * 재시작 시 브로커 잔고 기반 포지션 복원 이벤트 (운영 1일차 ⑨).
 *
 * <p>PositionBook은 인메모리라 재시작하면 사라진다(의도된 한계 — PLAN 9절).
 * 그 결과 재기동 후에는 실제로 보유 중인 종목도 "미보유"로 간주돼 매도 시그널이
 * 거부된다(2026-09-11: 19주 보유 상태로 주말을 넘기게 되면서 실제 문제화).
 * execution.PositionRestorer가 기동 시 kt00018 잔고의 보유 배열을 읽어 종목당
 * 하나씩 이 이벤트를 발행하고, portfolio.PositionBook이 수신해 시드한다 —
 * Fill을 위조하지 않으므로 슬리피지·실현손익 집계를 오염시키지 않는다.
 */
public record PositionRestored(String symbol, long quantity, BigDecimal avgPrice) {
}

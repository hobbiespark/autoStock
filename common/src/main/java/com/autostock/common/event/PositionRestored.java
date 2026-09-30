package com.autostock.common.event;

import com.autostock.common.util.Price;
import com.autostock.common.util.StockCode;

/**
 * 재시작 시 브로커 잔고 기반 포지션 복원 이벤트 (운영 1일차 ⑨).
 *
 * <p>PositionBook은 인메모리라 재시작하면 사라진다(의도된 한계 — PLAN 9절).
 * 그 결과 재기동 후에는 실제로 보유 중인 종목도 "미보유"로 간주돼 매도 시그널이
 * 거부된다(2026-09-11: 19주 보유 상태로 주말을 넘기게 되면서 실제 문제화).
 * execution.PositionRestorer가 기동 시 kt00018 잔고의 보유 배열을 읽어 종목당
 * 하나씩 이 이벤트를 발행하고, portfolio.PositionBook이 수신해 시드한다 —
 * Fill을 위조하지 않으므로 슬리피지·실현손익 집계를 오염시키지 않는다.
 *
 * <p>symbol은 {@link StockCode}다. JSON에서는 기존과 같은 문자열이다(JacksonConfig).
 *
 * <p>avgPrice는 {@link Price}이고 <b>null일 수 있다</b> — 잔고 응답에서 매입가를 찾지 못하면 "평단 모름"이다
 * (예전에는 0을 넣어, 그 값이 강제 청산 지정가로 쓰이면 0원 주문이 나갔다 — 사용자 결정 2026-09-30).
 */
public record PositionRestored(StockCode symbol, long quantity, Price avgPrice) {
}

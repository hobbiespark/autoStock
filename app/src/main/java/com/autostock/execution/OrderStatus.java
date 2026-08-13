package com.autostock.execution;

/**
 * 주문 상태. {@code orders} 테이블의 {@code status} 컬럼 값과 1:1 대응한다(name()을 그대로 저장).
 * <pre>
 *   SUBMITTED : 접수됨(SIM은 즉시체결 전 잠깐, LIVE는 체결통보 오기 전까지 이 상태로 머문다)
 *   FILLED    : 체결 완료(SIM 즉시체결, 또는 LIVE 체결통보 수신)
 *   CANCELLED : 미체결 타임아웃 등으로 취소됨(StaleOrderCanceller)
 *   REJECTED  : 브로커가 접수 자체를 거부함(현재는 아직 이 상태로 전이하는 코드가 없음 —
 *               향후 주문 접수 실패 처리 추가 시 사용할 자리만 마련해 둠)
 * </pre>
 */
public enum OrderStatus {
    SUBMITTED,
    FILLED,
    CANCELLED,
    REJECTED
}

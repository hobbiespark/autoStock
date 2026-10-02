package com.autostock.risk;

import com.autostock.common.util.StockCode;

import java.util.Set;

/**
 * 미체결 매수 조회 포트 (실행 계획 1.2, BE-P1-2, 2026-10-02 — aiDoc/open-order-aware-risk.md).
 *
 * <p>{@link RiskGate}는 체결된 것만 아는 {@code portfolio.PositionBook}으로 "보유 중이면 추가 매수 금지"와
 * "동시 보유 한도"를 판단해 왔다. 그래서 매수 주문이 나가 체결을 기다리는 동안(전송~체결) 같은 종목 매수 시그널이
 * 또 오면 막지 못했다. 이 포트가 "결과가 아직 확정되지 않은 매수 주문이 걸린 종목"을 알려준다.
 *
 * <p>구현은 trading 모듈({@code trading.OpenOrderQueryAdapter}, 주문 테이블 조회)이 맡는다 — 의존 방향은
 * trading → risk로, {@link EquitySource}(execution이 구현)와 같은 모양이라 순환이 없다. risk는 주문 상태기계를 모른다.
 *
 * <p>조회가 실패하면 예외를 던져도 된다 — {@link RiskGate}가 그 매수를 거부한다(모르면 사지 않는다).
 * 매도와 수동 주문은 이 포트를 쓰지 않는다.
 */
@FunctionalInterface
public interface OpenOrderQuery {

    /**
     * 결과가 확정되지 않은 매수 주문이 걸린 종목들 — 전송 중·접수·부분 체결·취소 요청·결과 불명.
     * 날짜와 무관하다(어제의 UNKNOWN도 아직 포지션이 될 수 있다). 없으면 빈 집합.
     */
    Set<StockCode> symbolsWithOpenBuy();
}

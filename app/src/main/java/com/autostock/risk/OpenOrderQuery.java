package com.autostock.risk;

import com.autostock.common.event.Side;
import com.autostock.common.util.StockCode;

import java.math.BigDecimal;
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

    /**
     * 이 전략의 결과가 확정되지 않은 주문이 걸린 종목(2026-10-16, 다전략 슬리브 — aiDoc/multi-strategy-paper-2026-10-16.md).
     * 슬리브가 있는 전략의 매수 중복·동시 보유와 매도 중복을 그 전략 기준으로 본다.
     *
     * <p>기본 구현은 보수적이다 — 매수는 계좌 전체 미체결 매수, 매도는 없음(시험의 람다 구현 호환). 실제 구현
     * ({@code trading.OpenOrderQueryAdapter})은 전략 ID(대소문자 무시)로 거른다.
     */
    default Set<StockCode> symbolsWithOpenOrder(String strategyId, Side side) {
        return side == Side.BUY ? symbolsWithOpenBuy() : Set.of();
    }

    /**
     * 결과가 확정되지 않은 매수 주문의 남은 금액 합 — 지정가 × (주문 수량 − 체결 수량). 운용 자금 한도(2026-10-21,
     * {@link TradingCapitalProperties})가 "이미 쓰기로 한 돈"으로 센다. 어제의 UNKNOWN도 넣는다(아직 포지션이 될 수 있다).
     *
     * <p>기본 구현은 0이다(시험의 람다 구현 호환). 실제 구현({@code trading.OpenOrderQueryAdapter})은 주문 표에서 센다.
     * 조회가 실패하면 예외를 던져도 된다 — {@link RiskGate}가 그 매수를 거부한다.
     */
    default BigDecimal openBuyAmount() {
        return BigDecimal.ZERO;
    }
}

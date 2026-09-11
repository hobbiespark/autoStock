package com.autostock.common.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 전략이 발행하는 매매 시그널. (스키마 v2 — fixedQuantity 추가)
 * 시그널은 주문이 아니다 — risk 모듈 게이트 통과 후에만 OrderRequest가 된다.
 *
 * <p><b>fixedQuantity (2026-09-11 추가, 운영 1일차 ⑦)</b>: 대시보드 수동 시그널처럼
 * "사람이 수량을 직접 지정"하는 경우에만 채운다. null이면(전략 발행 시그널의 기본)
 * RiskGate가 기존대로 자동 사이징한다. 값이 있어도 RiskGate의 상한(예산 캡·보유량 캡)을
 * 넘을 수 없다 — 수동 지정은 "자동 산정치 이하로 줄이는" 용도이지 리스크 한도를 우회하는
 * 수단이 아니다. 과거 이벤트(v1, 필드 없음)는 역직렬화 시 null로 채워져 하위호환된다.
 */
public record Signal(
        String strategyId,
        String symbol,
        Side side,
        BigDecimal refPrice,
        double confidence,
        Long fixedQuantity,
        Instant timestamp
) {

    /** v1 호환 생성자 — 기존 호출부(전략·테스트)는 fixedQuantity 없이 그대로 컴파일된다. */
    public Signal(String strategyId, String symbol, Side side, BigDecimal refPrice,
                  double confidence, Instant timestamp) {
        this(strategyId, symbol, side, refPrice, confidence, null, timestamp);
    }
}

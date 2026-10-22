package com.autostock.common.event;

import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import com.autostock.common.util.StockCode;

import java.time.Instant;

/**
 * risk 게이트를 통과한 주문 요청. (스키마 v1 — 필드 자체는 유지, 값의 "형식"만 진화)
 * idempotencyKey: 중복 주문 방지 멱등키 — execution은 동일 키 재수신 시 무시.
 * PLAN.md ADR-6 7절부터는 UUID 대신 {@code com.autostock.common.util.ClientOrderId}
 * 포맷 문자열("20260813-BREAKOUT-005930-BUY-001")이 담긴다 — 스키마(필드 이름·타입)는
 * 바뀌지 않았으므로 이벤트 계약 버전은 그대로 v1이다.
 * symbol은 {@link StockCode}다(ARCHITECTURE.md 3절) — JSON에서는 여전히 문자열("005930")로 직렬화되어
 * 저장 형식이 같으므로 역시 v1이다(app config.JacksonConfig).
 * limitPrice는 {@link Price}다 — 모든 주문이 지정가(키움 trde_tp=0)라 항상 있다. 가격이 없거나 0이면
 * risk가 주문 요청을 만들지 않는다. JSON은 같은 숫자 그대로라 역시 v1이다.
 *
 * <p><b>benchmarkPrice (2026-10-22, 스키마 v2)</b>: 슬리피지 기준가 — {@link Signal#benchmarkPrice()}를 그대로 옮긴다.
 * null이면 지정가가 기준이다. 주문 실행에는 쓰지 않는다(계측용). 과거 이벤트는 null로 역직렬화된다.
 */
public record OrderRequest(
        String idempotencyKey,
        String strategyId,
        StockCode symbol,
        Side side,
        Quantity quantity,
        Price limitPrice,
        Instant timestamp,
        Price benchmarkPrice
) {
    // 호환 생성자를 두지 않는다 — 생성자 위임도 "주문 요청 생성"이라 ArchitectureRulesTest(risk·backtest만 생성)에 걸린다.
    // 기준가가 없으면 null을 넘긴다(백테스트·시험).
}

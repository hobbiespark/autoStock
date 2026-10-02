package com.autostock.execution;

import com.autostock.common.util.Price;
import com.autostock.common.util.StockCode;

/**
 * 잔고(kt00018)의 종목별 보유 한 건 (실행 계획 1.5, BE-P2-1, 2026-10-02 — aiDoc/position-reconcile.md).
 *
 * <p>예전에는 {@link BrokerBalance#holdings()}가 키움 원시 Map을 그대로 내보내 포트 밖({@link PositionRestorer})이 키움
 * 필드 이름을 알아야 했다(ARCHITECTURE.md 4절 위반). 이제 어댑터({@link KiwoomBrokerAdapter})가 실측 필드(2026-09-11 —
 * stk_cd·stk_nm·rmnd_qty·pur_pric)를 이 타입으로 바꾼다. 필드 규칙(후보 키, A 접두 제거, 0 평단은 미상)은 예전과 같다.
 *
 * @param symbol   종목코드(A 접두 제거)
 * @param name     종목명(stk_nm) — 없으면 빈 문자열
 * @param quantity 보유 수량(1 이상)
 * @param avgPrice 매입 평단 — 응답에 없거나 0이면 null(평단 미상, 0을 지어내지 않는다)
 */
public record BrokerHolding(StockCode symbol, String name, long quantity, Price avgPrice) {

    public BrokerHolding {
        if (symbol == null) {
            throw new IllegalArgumentException("symbol은 null일 수 없다");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity는 1 이상이어야 한다: " + quantity);
        }
        name = name == null ? "" : name;
    }
}

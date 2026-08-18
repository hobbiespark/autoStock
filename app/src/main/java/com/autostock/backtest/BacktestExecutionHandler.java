package com.autostock.backtest;

import com.autostock.common.event.Fill;
import com.autostock.common.event.OrderRequest;

import java.math.BigDecimal;

/**
 * 백테스트용 "가짜 거래소" — 주문을 받으면 그 자리에서 즉시 체결시켜준다.
 *
 * <p>라이브에서는 주문(OrderRequest)을 브로커에 보내고 비동기로 체결(Fill) 이벤트를
 * 스프링 이벤트로 받는다({@code TradingService} 참고). 백테스트에는 브로커가 없으므로,
 * 대신 이 클래스가 "지금 이 가격이면 체결됐을 것이다"를 {@link CostModel}로 계산해
 * 즉시 {@link Fill}을 만들어 돌려준다.
 *
 * <p><b>스프링 이벤트가 아니다</b> — {@code @EventListener}로 비동기 수신하는 구조가 아니라,
 * 백테스트 루프({@link BacktestRunner}) 안에서 "메서드 호출 → 즉시 반환값"으로 동기 처리한다.
 * 이유: 백테스트는 수만 개의 캔들을 순서대로 빠르게 돌려야 하는데, 매 체결마다 이벤트
 * 발행/구독 오버헤드를 낼 필요가 없다. 전략·리스크 코드(라이브와 공유하는 부분)만
 * 이벤트 기반이면 충분하다.
 */
public final class BacktestExecutionHandler {

    private final CostModel costModel;

    public BacktestExecutionHandler(CostModel costModel) {
        this.costModel = costModel;
    }

    /**
     * 주문을 즉시 체결시킨다.
     *
     * @param order          체결할 주문 (수량은 이미 결정되어 있다고 가정)
     * @param referencePrice 체결 기준가 — 룩어헤드 방지를 위해 호출자가 "다음 봉의 시가"
     *                       등 아직 전략이 알 수 없었던 가격을 넘겨야 한다({@link BacktestRunner} 참고)
     * @return CostModel이 적용된(슬리피지 반영) 체결가로 만들어진 Fill
     */
    public Fill execute(OrderRequest order, BigDecimal referencePrice) {
        BigDecimal execPrice = switch (order.side()) {
            case BUY -> costModel.slippageAdjustedBuyPrice(referencePrice);
            case SELL -> costModel.slippageAdjustedSellPrice(referencePrice);
        };
        return new Fill(
                order.idempotencyKey(),
                "BACKTEST",   // 브로커 주문번호가 없으므로 고정 표식 사용
                order.symbol(),
                order.side(),
                order.quantity(),
                execPrice,
                order.timestamp()
        );
    }
}

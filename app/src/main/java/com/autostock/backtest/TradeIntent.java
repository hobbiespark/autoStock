package com.autostock.backtest;

import java.math.BigDecimal;

/**
 * 장중 체결 조건부 매매 의사 — 종가 시그널 모델(과거 {@code BacktestStrategy}의
 * {@code Optional<Side>})로는 표현할 수 없는 "장중 돌파 진입/손절"을 표현하기 위한 확장.
 *
 * <p>쉬운 설명: 변동성 돌파 전략처럼 "오늘 장중에 어떤 가격을 넘으면 산다"는 조건부 주문은,
 * 하루가 다 끝난 뒤(종가 확정 후)에야 판단하는 옛 모델로는 표현이 안 된다(옛 모델은
 * "오늘 종가 신호 → 내일 시가 체결"만 가능했다). TradeIntent는 "오늘 이 조건이 되면
 * 체결하라"는 조건부 주문 자체를 값으로 표현한다. 체결 여부 판정(오늘 고가/저가와
 * 트리거 가격 비교)은 전략이 아니라 {@link BacktestRunner}가 담당한다 — 전략은 "무엇을
 * 걸지"만 정하고, 러너가 "그래서 체결됐는지"를 사후적으로 그날 캔들을 보고 판단한다.
 *
 * @param kind  이 의사의 종류
 * @param price 트리거/스탑 가격. {@link Kind#SELL_NEXT_OPEN}은 가격이 필요 없으므로 {@code null}.
 */
public record TradeIntent(Kind kind, BigDecimal price) {

    /** TradeIntent의 종류. */
    public enum Kind {
        /** 당일 장중 고가가 {@link #price} 이상에 도달하면 매수. */
        BUY_STOP,
        /** 다음 체결 기회(오늘 시가 — 전략 호출 시점에 이미 알려진 가격)에 전량 매도. */
        SELL_NEXT_OPEN,
        /** 당일 장중 저가가 {@link #price} 이하로 내려가면 매도(손절). */
        SELL_STOP
    }

    /**
     * 매수 스탑(장중 돌파 매수). 체결 규칙: 당일 고가가 triggerPrice 이상이면 체결,
     * 체결가 = max(triggerPrice, 당일 시가) — 이미 시가가 트리거를 넘겨 갭상승 출발했다면
     * 트리거 가격이 아니라 시가에 체결된다(실제로는 그보다 싸게 살 수 없었으므로).
     */
    public static TradeIntent buyStop(BigDecimal triggerPrice) {
        return new TradeIntent(Kind.BUY_STOP, triggerPrice);
    }

    /**
     * 단순 시장가 매수의 편의 팩토리 — buyStop(당일 시가)와 동치다. 당일 고가는 항상 시가
     * 이상이므로(캔들 정의상) 트리거가 시가와 같으면 반드시 그날 시가에 체결된다.
     * 종가 신호 모델(첫 봉 매수 후 보유 등)을 새 인터페이스로 옮길 때 쓰기 위한 편의 메서드.
     */
    public static TradeIntent buyAtOpen(BigDecimal openPrice) {
        return new TradeIntent(Kind.BUY_STOP, openPrice);
    }

    /** 다음 시가(전략 호출 시점에 알려진 오늘 시가) 전량 매도 — 단기 보유 전략의 표준 청산 방식. */
    public static TradeIntent sellNextOpen() {
        return new TradeIntent(Kind.SELL_NEXT_OPEN, null);
    }

    /**
     * 매도 스탑(손절). 체결 규칙: 당일 저가가 stopPrice 이하면 체결,
     * 체결가 = min(stopPrice, 당일 시가) — 이미 시가가 손절가 아래로 갭하락 출발했다면
     * 손절가가 아니라 시가에 체결된다(실제로는 그보다 비싸게 팔 수 없었으므로).
     */
    public static TradeIntent sellStop(BigDecimal stopPrice) {
        return new TradeIntent(Kind.SELL_STOP, stopPrice);
    }
}

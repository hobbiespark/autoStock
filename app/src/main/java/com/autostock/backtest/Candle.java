package com.autostock.backtest;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 일봉(하루치 시가/고가/저가/종가/거래량) 하나를 표현하는 값 객체.
 *
 * <p>백테스트는 이 캔들들을 시간순으로 하나씩 재생(replay)하며 진행된다.
 * 라이브 매매의 {@code MarketTick}이 "지금 이 순간의 체결가 한 틱"을 나타낸다면,
 * {@code Candle}은 "하루가 끝난 뒤 집계된 요약"이다 — 그래서 백테스트 루프는
 * 틱 단위가 아니라 일봉 단위로 전략을 호출한다(PLAN 4-2절, 일봉 기준 스윙 전략 가정).
 *
 * @param symbol 종목코드 (예: "005930")
 * @param date   거래일
 * @param open   시가
 * @param high   고가
 * @param low    저가
 * @param close  종가
 * @param volume 거래량
 */
public record Candle(
        String symbol,
        LocalDate date,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume
) {
}

package com.autostock.common.event;

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
 * <p><b>common 모듈에 두는 이유</b>: 원래 backtest 모듈 전용 타입이었으나,
 * market 모듈의 {@code KiwoomDailyChartService}가 키움 REST(ka10081) 응답을
 * 이 타입으로 변환해 내놓아야 해서 두 모듈이 함께 참조하게 됐다. Spring Modulith
 * 경계상 모듈 간 타입 공유는 OPEN 모듈인 common(= 모듈 간 통신 계약)을 통해서만
 * 허용되므로(ModularityTests 참고) 여기로 옮겼다 — {@link Fill}, {@link OrderRequest}와
 * 같은 이유.
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

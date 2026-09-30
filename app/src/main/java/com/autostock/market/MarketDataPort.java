package com.autostock.market;

import com.autostock.common.event.Candle;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 시세 조회 포트 — market과 그 사용처(monitor 등)가 아는 것은 이 인터페이스와 아래 값뿐이다
 * (ARCHITECTURE.md 4절, execution.BrokerPort와 같은 구조). 브로커 응답 형식·필드명은 구현체
 * ({@link KiwoomMarketDataAdapter}) 밖으로 나오지 않는다. 주문·잔고는 캐시 없는 BrokerPort 경로를 쓴다.
 */
public interface MarketDataPort {

    /** 현재가·당일 시고저. */
    StockQuote stockQuote(String symbol);

    /** 최우선 매도·매수 호가. */
    BestQuote bestQuote(String symbol);

    /** 기준일부터 과거 방향 일봉, 날짜 오름차순. 한 페이지 분량(연속조회 미구현). */
    List<Candle> dailyCandles(String symbol, LocalDate baseDate);

    /** 최근 1분봉 한 페이지(약 2.3거래일). 정렬은 보장하지 않는다. */
    List<MinuteBar> minuteBars(String symbol);

    /** 값이 없으면 필드는 null. */
    record StockQuote(String name, BigDecimal current, BigDecimal open, BigDecimal high, BigDecimal low) {
        public static final StockQuote EMPTY = new StockQuote(null, null, null, null, null);
    }

    /** 값이 없으면 필드는 null. */
    record BestQuote(BigDecimal bestAsk, BigDecimal bestBid) {
        public static final BestQuote EMPTY = new BestQuote(null, null);
    }

    /** 1분봉. time은 KST 체결 시각(분 시작). */
    record MinuteBar(LocalDateTime time, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
                     long volume, long accumulatedVolume) {
    }
}

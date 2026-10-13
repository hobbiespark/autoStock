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

    /**
     * 1분봉 한 페이지(약 900행 ≈ 2.3거래일) — 최신부터 과거 방향. 첫 페이지는 {@code nextKey = null},
     * 다음 페이지는 앞 페이지의 {@link MinuteBarPage#nextKey()}. 브로커가 주는 가장 오래된 분봉까지 가면 nextKey가 null이다
     * (키움 ka10080은 최근 1년 — 2026-09-11 실측). 페이지 안 정렬은 보장하지 않는다.
     */
    MinuteBarPage minuteBarPage(String symbol, String nextKey);

    /**
     * 한 시장의 상장 종목 전부(연속 조회로 끝까지) — 공모주 상장일·종목코드 자동 확인용(2026-10-13, {@link NewListingWatcher}).
     *
     * @param market 시장
     */
    List<ListedStock> listedStocks(ListingMarket market);

    /** 상장 종목 목록을 받는 시장 — 공모주가 상장하는 두 곳. */
    enum ListingMarket {
        KOSPI("코스피"),
        KOSDAQ("코스닥");

        private final String label;

        ListingMarket(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 상장 종목 한 줄. 상장일을 못 읽으면 {@code listedOn}이 null.
     *
     * @param marketName 브로커가 주는 시장명(예: "거래소", "코스닥")
     */
    record ListedStock(String code, String name, LocalDate listedOn, String marketName) {
    }

    /** 값이 없으면 필드는 null. */
    record StockQuote(String name, BigDecimal current, BigDecimal open, BigDecimal high, BigDecimal low) {
        public static final StockQuote EMPTY = new StockQuote(null, null, null, null, null);
    }

    /** 값이 없으면 필드는 null. */
    record BestQuote(BigDecimal bestAsk, BigDecimal bestBid) {
        public static final BestQuote EMPTY = new BestQuote(null, null);
    }

    /** 분봉 한 페이지와 다음 페이지 키(마지막 페이지면 null). */
    record MinuteBarPage(List<MinuteBar> bars, String nextKey) {
        public boolean hasNext() {
            return nextKey != null;
        }
    }

    /** 1분봉. time은 KST 체결 시각(분 시작). */
    record MinuteBar(LocalDateTime time, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
                     long volume, long accumulatedVolume) {
    }
}

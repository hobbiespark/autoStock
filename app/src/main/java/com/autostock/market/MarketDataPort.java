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

    /**
     * 전일대비 등락률 상위 — 상승률순, KRX, 상하한가 미포함(2026-10-16, 종가→익일 전략 후보 — aiDoc/multi-strategy-paper-2026-10-16.md 3-2).
     * 키움 ka10027. 한 번 호출의 첫 페이지만 준다(실측 10/16: 조건을 걸면 160행 안팎, 연속조회 없음).
     *
     * @param stockCondition        종목조건 코드(키움): 16 ETF+ETN 제외, 4 우선주+관리주 제외, 5 증거금100% 제외, 11 정리매매 제외
     * @param priceCondition        가격조건 코드: 8 = 1천 원 이상
     * @param tradingValueCondition 거래대금조건 코드: 1000 = 100억 원 이상
     * @return 상승률 내림차순. 구현이 없으면 빈 목록
     */
    default List<RankedStock> risingRanking(String stockCondition, String priceCondition, String tradingValueCondition) {
        return List.of();
    }

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

    /**
     * 등락률 순위 한 행(ka10027).
     *
     * @param current    현재가(원, 부호 뗀 값)
     * @param changeRate 등락률(%, 예: 12.34)
     * @param volume     당일 거래량(주)
     */
    record RankedStock(String code, String name, BigDecimal current, BigDecimal changeRate, long volume) {

        /** 거래대금 근사(현재가 × 거래량, 원). */
        public BigDecimal tradingValue() {
            return current == null ? BigDecimal.ZERO : current.multiply(BigDecimal.valueOf(volume));
        }
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

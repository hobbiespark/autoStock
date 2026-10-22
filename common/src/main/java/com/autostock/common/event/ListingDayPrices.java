package com.autostock.common.event;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 최근 상장 종목의 상장일 시세 — 공모주 청약 추천의 "시장 분위기"(직전 상장 5곳의 상장일 종가 수익률) 재료(2026-10-15,
 * aiDoc/ipo-subscription-strategy.md). market 모듈이 거래일 15:40에 키움 일봉으로 확인해 발행하고, ipo 모듈이 종목코드로 공모주
 * 딜과 맞춰 남긴다. market → ipo 타입 의존이 생기지 않도록 common 이벤트로만 통신한다({@link RecentListings}와 같은 이유).
 *
 * @param day     확인한 날(KST)
 * @param entries 상장일이 최근인 코스피·코스닥 종목(스팩·ETF·ETN 제외)의 상장일 시세
 */
public record ListingDayPrices(LocalDate day, List<Entry> entries) {

    public ListingDayPrices {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /**
     * @param stockCode 종목코드
     * @param name      종목명
     * @param listedOn  상장일
     * @param open      상장일 시가
     * @param high      상장일 고가
     * @param low       상장일 저가
     * @param close     상장일 종가
     */
    public record Entry(String stockCode, String name, LocalDate listedOn, BigDecimal open, BigDecimal high, BigDecimal low,
                        BigDecimal close) {
    }
}

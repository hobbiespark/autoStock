package com.autostock.common.event;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 오늘 상장한 종목의 시초가 — 공모주 상장일 09:05 "시초가·공모가 대비 수익률" 알림 재료(2026-10-30, 사용자 공모주 알림 강화,
 * aiDoc/ipo-automation.md "2026-10-30 바뀜"). market 모듈이 거래일 09:05에 키움 현재가(시가)로 확인해 발행하고, ipo 모듈이 종목코드로
 * 관심 공모주 딜과 맞춰 알린다. market → ipo 타입 의존이 생기지 않도록 common 이벤트로만 통신한다({@link ListingDayPrices}와 같은 이유).
 *
 * @param day     확인한 날(KST) — 상장일
 * @param entries 오늘 상장한 코스피·코스닥 종목(ETF·ETN·리츠 제외, 스팩 포함)의 시초가
 */
public record ListingOpenPrices(LocalDate day, List<Entry> entries) {

    public ListingOpenPrices {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /**
     * @param stockCode 종목코드
     * @param name      종목명
     * @param open      오늘 시가(시초가)
     * @param current   확인 시점 현재가(모르면 null)
     */
    public record Entry(String stockCode, String name, BigDecimal open, BigDecimal current) {
    }
}

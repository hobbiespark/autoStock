package com.autostock.common.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;

/**
 * 거래 캘린더 — "오늘 장이 서나", "지금 정규장 시간인가"를 답하는 순수 함수 모음.
 *
 * <p>순수 함수인 이유: 외부 상태(시계·네트워크) 없이 인자만으로 결정되므로 테스트가 쉽고,
 * 백테스트·라이브 어디서든 같은 판정을 재현할 수 있다(ARCHITECTURE.md 규칙 17·18 — 다른
 * strategy/backtest 순수 함수들과 같은 원칙).
 *
 * <p><b>휴장일 목록은 2026년 한국 증시 기준으로 하드코딩되어 있다.</b>
 * TODO: 매년 갱신 필요 — 한국거래소(KRX) 휴장일 공지를 확인해 다음 해 목록으로 교체할 것.
 * 대체공휴일 계산은 자동화하지 않고 값 자체를 박아 넣었다(날짜 계산 규칙이 매년 법 개정
 * 영향을 받아 복잡하고, 어차피 매년 손으로 갱신해야 하므로 자동화 이득이 적다고 판단).
 * 아래 각 날짜에 산정 근거를 주석으로 남겼다 — 대체공휴일 판단은 공식 공지 대조 전까지
 * "정확성 불확실"로 표기한다.
 */
public final class TradingCalendar {

    private TradingCalendar() {
        // 순수 정적 메서드 모음 — 인스턴스화 불필요
    }

    /**
     * 2026년 한국 증시 휴장일. 주말은 별도로 {@link #isTradingDay}에서 걸러지므로
     * 여기에는 평일 휴장일만 담는다.
     */
    private static final Set<LocalDate> HOLIDAYS_2026 = Set.of(
            LocalDate.of(2026, 1, 1),   // 신정
            LocalDate.of(2026, 2, 16),  // 설 연휴
            LocalDate.of(2026, 2, 17),  // 설날
            LocalDate.of(2026, 2, 18),  // 설 연휴
            LocalDate.of(2026, 3, 2),   // 삼일절(3/1) 대체공휴일 — 정확성 불확실, 거래소 공지 대조 필요
            LocalDate.of(2026, 5, 5),   // 어린이날
            LocalDate.of(2026, 5, 25),  // 석가탄신일(5/24) 대체공휴일 — 정확성 불확실, 거래소 공지 대조 필요
            LocalDate.of(2026, 6, 8),   // 현충일(6/6) 대체공휴일 — 정확성 불확실, 거래소 공지 대조 필요
            LocalDate.of(2026, 8, 17),  // 광복절(8/15) 대체공휴일 — 정확성 불확실, 거래소 공지 대조 필요
            LocalDate.of(2026, 9, 24),  // 추석 연휴
            LocalDate.of(2026, 9, 25),  // 추석
            LocalDate.of(2026, 9, 26),  // 추석 연휴
            LocalDate.of(2026, 10, 5),  // 개천절(10/3) 대체공휴일 — 정확성 불확실, 거래소 공지 대조 필요
            LocalDate.of(2026, 10, 9),  // 한글날
            LocalDate.of(2026, 12, 25), // 성탄절
            LocalDate.of(2026, 12, 31)  // 연말휴장(증권시장 관행상 휴장일 — 거래소 공지로 매년 확정)
    );

    /**
     * 주어진 날짜가 거래일인지 판정한다. 주말이거나 {@link #HOLIDAYS_2026}에 있으면 거래일이 아니다.
     *
     * <p>목록에 없는 연도(2026년 외)는 주말 판정만 적용되고 휴장일은 전혀 걸러지지 않는다 —
     * 목록을 매년 갱신해야 하는 이유이자 한계다(클래스 설명 TODO 참고).
     */
    public static boolean isTradingDay(LocalDate date) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return false;
        }
        return !HOLIDAYS_2026.contains(date);
    }

    /**
     * 주어진 일시가 정규장 시간(09:00~15:30 KST) 이내인지 판정한다.
     * 요일·휴장일은 보지 않는다 — "시각"만의 판정이다(거래일 여부는 {@link #isTradingDay} 책임).
     */
    public static boolean isMarketHours(LocalDateTime dateTime) {
        LocalTime time = dateTime.toLocalTime();
        return !time.isBefore(MarketConstants.MARKET_OPEN) && !time.isAfter(MarketConstants.MARKET_CLOSE);
    }
}

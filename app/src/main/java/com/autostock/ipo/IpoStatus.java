package com.autostock.ipo;

/**
 * 공모주 딜 진행 상태 (PLAN.md ADR-9, 트랙 E2).
 *
 * <p>{@link IpoSyncScheduler}가 매 배치마다 오늘 날짜와 딜의 청약 일정을 비교해 재계산한다
 * (자유 전이 — {@code trading.OrderStatus}처럼 엄격한 상태기계를 두지 않는다. 청약 일정이
 * 정정 공시로 바뀌면 날짜도 갱신되므로 상태가 뒤로 되돌아갈 수 있다).
 */
public enum IpoStatus {
    /** 청약 시작 전. */
    UPCOMING,
    /** 청약 기간 중(오늘이 subscriptionStart~subscriptionEnd 사이). */
    SUBSCRIBING,
    /** 상장일 도달(listingDate가 있고 오늘이 그 이후) — listingDate는 DART가 제공하지 않아
     *  수동 입력 전까지는 이 상태에 도달하지 않는다(한계, 클래스 Javadoc 참고). */
    LISTED,
    /** 청약 기간이 지났지만 상장일 정보가 없어 더 추적할 수 없는 상태. */
    PASSED
}

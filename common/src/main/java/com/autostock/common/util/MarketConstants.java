package com.autostock.common.util;

import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 국내 주식시장 관련 공용 상수 — KST 타임존, 정규장 시작/종료, 정리(청산) 시각.
 *
 * <p>왜 공통화했나 — 여러 모듈이 {@code ZoneId.of("Asia/Seoul")}을 각자 선언해 두면
 * 하나만 고치고 나머지를 빠뜨리는 사고가 난다. 서버가 UTC로 돌아도 "장이 열려 있는가",
 * "오늘 하루가 언제 바뀌는가" 같은 판단은 항상 KST 기준이어야 하므로(PLAN 9절 운영 스케줄),
 * 이 상수 하나로 고정한다.
 */
public final class MarketConstants {

    private MarketConstants() {
        // 순수 정적 상수 모음 — 인스턴스화 불필요
    }

    /** 한국 표준시. 날짜 롤오버·장 시간 판정은 항상 이 기준. */
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 정규장 시작 시각 09:00 (KST). */
    public static final LocalTime MARKET_OPEN = LocalTime.of(9, 0);

    /** 정규장 종료 시각 15:30 (KST). */
    public static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    /** 장 마감 전 정리(신규 진입 중단·전량 청산 옵션) 시각 15:20 (KST, PLAN 9절 운영 스케줄). */
    public static final LocalTime MARKET_WIND_DOWN = LocalTime.of(15, 20);
}

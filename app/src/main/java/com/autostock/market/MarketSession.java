package com.autostock.market;

/**
 * 시장 세션 — 상시 실행 프로세스가 지금 "장 대응" 상태여야 하는지를 나타낸다
 * ({@link MarketSessionService}가 판정).
 *
 * <p>{@code monitor.TradingSystemStatus}(운영자가 매매를 허가했는가)와는 직교하는 축이다 —
 * RUNNING이어도 장외 시간이면 STANDBY이고, 이때는 장 대응 작업(WS 연결·주기 대사·미체결
 * 취소 점검)만 쉰다. 운영 상태기계는 건드리지 않으므로 아침에 사람이 다시 [시작]을
 * 누를 필요가 없다.
 */
public enum MarketSession {

    /** 장 대응 시간대(기본 거래일 08:30~16:00 KST) — 모든 장중 작업이 정상 동작. */
    ACTIVE,

    /** 장외 대기(기본 16:00~익거래일 08:30, 주말·휴장일 종일) — 장중 작업을 쉬고 브로커 호출을 멈춘다. */
    STANDBY
}

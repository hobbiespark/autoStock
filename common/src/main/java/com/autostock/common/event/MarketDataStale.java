package com.autostock.common.event;

import java.time.Instant;

/**
 * 시세(WebSocket) 장시간 단절 이벤트. (스키마 v1)
 *
 * <p>market-data 모듈(KiwoomWebSocketClient)의 watchdog이 연속 단절이 임계치
 * ({@code autostock.ws.stale-after}, 기본 180초)를 넘었을 때 발행한다. risk 모듈이
 * 이 이벤트를 구독해 킬스위치를 작동시킨다("시세 없는 채로 매매하는 사고 방지", PLAN 3절) —
 * market-data는 risk의 존재를 전혀 모른 채 상태만 이벤트로 알리고, risk가 그걸
 * "비상 정지 사유"로 해석한다(모듈 경계 원칙, KillSwitchChanged와 동일한 설계).
 *
 * @param disconnectedSince 단절이 시작된 것으로 판단한 시각(첫 watchdog 감지 시각)
 * @param seconds            disconnectedSince로부터 이 이벤트가 발행된 시점까지 경과한 초
 */
public record MarketDataStale(
        Instant disconnectedSince,
        long seconds
) {
}

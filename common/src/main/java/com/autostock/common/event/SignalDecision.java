package com.autostock.common.event;

import java.time.Instant;
import java.util.Map;

/**
 * 종목 선정(또는 배제) 판단 근거 이벤트. (스키마 v1, FE-6 — PLAN.md ADR-10 확장표)
 *
 * <p>{@link Signal}이 "사고/팔고 싶다"는 의견이고 {@link OrderRequest}가 그 의견이 리스크
 * 게이트를 통과한 결과라면, 이 이벤트는 "왜 그런 의견을 냈는지/왜 거부됐는지"를 사람이
 * 재구성할 수 있도록 남기는 감사 기록이다. 전략(strategy)의 09:05 일일 판단 루프와
 * risk의 게이트 거부 지점, 양쪽 모두에서 종목 하나당 1건씩 발행된다 — "왜 오늘 이
 * 종목을 사고/안 샀나"를 화면(FE-6)에서 되짚어볼 수 있게 하는 것이 목적이다
 * (ARCHITECTURE.md "Strategy는 Signal까지만" 원칙 — 판단 근거가 곧 감사 대상).
 *
 * @param horizon    보유기간 지평(ADR-11): "MID"(중기, 현재 C3) / "DAY"(단타) / "SWING"(스윙) /
 *                   "LONG"(장기). 향후 다른 지평의 전략이 추가되면 그 전략이 같은 필드를 쓴다.
 * @param strategyId 판단을 내린 전략 ID(Signal.strategyId와 동일 값, RiskGate 거부 시에도
 *                   원본 Signal의 strategyId를 그대로 싣는다)
 * @param symbol     종목코드
 * @param conclusion 최종 결론: BUY(매수 시그널 발행) / SELL(매도 시그널 발행) /
 *                   HOLD(보유 유지, 신규 행동 없음) / SKIP(신규 진입 보류, 미보유 유지) /
 *                   REJECTED(RiskGate 게이트에서 거부됨 — Signal은 발행됐으나 주문으로
 *                   이어지지 못함)
 * @param reason     결론에 대한 한 줄 사유. RiskGate 거부 사유는 실제 로그 메시지 문구를
 *                   그대로 재사용한다(문구 이원화 방지).
 * @param metrics    판단에 쓰인 지표값(문자열로 직렬화) — 예: 모멘텀 N일 수익률, 국면
 *                   ON/OFF 여부, 국면 지수 종가/SMA200, 변동성 타게팅 산출 비중 등.
 *                   Signal 스키마를 바꾸지 않고 판단 근거를 별도로 남기기 위한 필드다.
 * @param decidedAt  판단 시각
 */
public record SignalDecision(
        String horizon,
        String strategyId,
        String symbol,
        String conclusion,
        String reason,
        Map<String, String> metrics,
        Instant decidedAt
) {
}

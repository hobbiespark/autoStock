package com.autostock.common.event;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 전략 정기 실행 완료 이벤트. (스키마 v1 — 2026-10-06 신규, aiDoc/market-hours-automation.md)
 *
 * <p>strategy 모듈이 하루 한 번의 정기 실행(국면 판정까지 간 실행)을 마치면 발행한다. monitor 모듈이 받아
 * 텔레그램 한 줄로 알리고, 장중 점검(09:15)에서 "오늘 실행했는가"를 확인하는 데 쓴다. 매매 판단에는 쓰지 않는다.
 *
 * @param strategyId 전략 ID(예: C3-MOMENTUM)
 * @param tradeDate  실행한 거래일(KST)
 * @param summary    사람이 읽는 요약 한 줄 — 실행 로그와 같은 문장
 * @param errors     종목 판단 오류 수(0이 아니면 WARN으로 알린다)
 * @param timestamp  완료 시각
 */
public record StrategyRunCompleted(
        String strategyId,
        LocalDate tradeDate,
        String summary,
        int errors,
        Instant timestamp
) {
}

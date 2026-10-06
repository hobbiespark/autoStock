package com.autostock.common.event;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 체결·미체결 TR 장중 실측 완료 이벤트. (스키마 v1 — 2026-10-06 신규, 실행 계획 1.6, aiDoc/market-hours-automation.md)
 *
 * <p>execution 모듈의 TrProbeJob이 정해진 날 한 번 실측(모의 서버 지정가 1주 매수 → 조회 → 취소 → 조회)을 마치면
 * 발행한다. monitor 모듈이 받아 텔레그램으로 알린다. 응답 원문은 docs/measured에 파일로 남는다.
 *
 * @param tradeDate 실측한 거래일(KST)
 * @param success   주문·취소·조회가 모두 응답을 받았는가(거부·오류가 하나라도 있으면 false)
 * @param summary   사람이 읽는 요약 한 줄
 * @param timestamp 완료 시각
 */
public record TrProbeCompleted(
        LocalDate tradeDate,
        boolean success,
        String summary,
        Instant timestamp
) {
}

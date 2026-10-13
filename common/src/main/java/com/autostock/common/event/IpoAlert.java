package com.autostock.common.event;

import java.time.Instant;

/**
 * 공모주 반자동 파이프라인(PLAN.md ADR-9, 트랙 E2) 알림 이벤트.
 *
 * <p>ipo 모듈은 이 이벤트를 발행하기만 하고, 실제 발송 수단(텔레그램 등)은 전혀 모른다 —
 * monitor 모듈의 리스너가 {@code Notifier}로 이어준다({@code monitor.TradeNotificationListener}와
 * 같은 브리지 패턴). ipo → monitor 방향 타입 의존이 생기지 않도록 common 이벤트로만 통신한다.
 *
 * @param corpName 종목명(발행회사명) — 신규 공모 모음 알림은 "공모주 신규"
 * @param phase    알림 단계(2026-10-13, ipo.IpoAlertPlanner) — "NEW"(신규 공모 모음), "METRICS"(수요예측 결과·확정 공모가·권고),
 *                 "D-1"(청약 시작 하루 전), "START"(청약 시작 당일), "END"(청약 마감일), "REFUND"(환불·납입일),
 *                 "LISTING-D1"(상장 전날), "LISTING"(상장일)
 * @param message  사람이 읽는 한국어 알림 본문(근거 지표 요약 포함)
 * @param at       발행 시각
 */
public record IpoAlert(String corpName, String phase, String message, Instant at) {
}

/**
 * 공모주 반자동 파이프라인 모듈 (PLAN.md ADR-9, 트랙 E2).
 *
 * <p>키움 REST API는 청약 TR을 제공하지 않는다(ADR-9 실측 확정) — 이 모듈은 ①일정 수집
 * ②필터 판단 ③알림까지만 자동화하고, ④청약 실행은 영웅문S#에서 수동으로 한다. 이 모듈이
 * 책임지는 범위는 수집(DART {@link com.autostock.ipo.DartClient})·판단({@link
 * com.autostock.ipo.IpoSyncScheduler} 필터 평가)·기록({@code ipo_deals} 테이블, 리프에
 * 가까운 자기완결 모듈)이다.
 *
 * <p>알림은 이 모듈이 직접 보내지 않는다 — {@link com.autostock.common.event.IpoAlert}
 * 이벤트만 발행하고, monitor 모듈의 리스너가 {@code Notifier}로 이어준다({@code
 * monitor.TradeNotificationListener}와 같은 브리지 패턴, ARCHITECTURE.md 9절). ipo →
 * monitor 방향 타입 의존은 없다(단방향, 순환 없음).
 *
 * <p>2026-10-13(aiDoc/ipo-automation.md): 공시 목록 전 쪽 수집·실패 재시도, 확정 공모가 자동 입력, 단계별 알림(신규·수요예측
 * 결과·청약 D-1·시작·마감·환불·상장 D-1·상장, 밤 22~07시 금지, {@code ipo_alert_log}), 상장일·종목코드 자동 확인 — market 모듈이
 * 발행하는 {@link com.autostock.common.event.RecentListings}를 받는다(market → ipo 타입 의존 없음, common 이벤트만).
 *
 * <p>monitor 모듈은 조회(GET /api/ipo)에 이 모듈의 {@link com.autostock.ipo.IpoDealRepository}를
 * 직접 참조한다 — monitor가 이미 trading.OrderRepository를 직접 참조하는 기존 패턴과 동일하다
 * (monitor/OrderHistoryController Javadoc 참고). 수동 입력(POST /api/ipo/{id}/record,
 * POST /api/ipo/{id}/metrics)은 {@link com.autostock.ipo.IpoDealCommandService}를 거친다.
 */
@org.springframework.modulith.ApplicationModule(displayName = "ipo")
package com.autostock.ipo;

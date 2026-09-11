package com.autostock.common.event;

import java.time.LocalDate;

/**
 * 재시작 시 당일 주문 일련번호 복원 이벤트 (운영 1일차 ①).
 *
 * <p>배경: ClientOrderId의 일련번호는 risk.DailyLimitTracker의 인메모리 카운터인데,
 * 앱 재시작으로 0으로 리셋되면 같은 날 첫 주문이 기존 키와 충돌한다(2026-09-11 실측 —
 * BUY-001~003 DB UNIQUE 충돌로 3회 스킵 후 -004에야 접수). trading 모듈이 기동 시
 * DB(orders)에서 당일 최대 일련번호를 읽어 이 이벤트로 발행하고, risk가 수신해
 * 카운터를 그 값 이상으로 프라이밍한다 — trading→risk 직접 참조 없이 이벤트로만.
 *
 * @param day          기준일(KST)
 * @param lastSequence 당일 관측된 최대 일련번호 (없으면 0)
 */
public record OrderSequenceRestored(LocalDate day, int lastSequence) {
}

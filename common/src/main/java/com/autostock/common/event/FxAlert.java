package com.autostock.common.event;

import java.time.Instant;

/**
 * 외화 적립 알림 이벤트(VALUNIVO 4-6, autoStock D-28).
 *
 * <p>fx 모듈은 이 이벤트를 발행하기만 하고 발송 수단은 모른다 — monitor 모듈의 리스너가 {@code Notifier}로 이어준다
 * ({@link IpoAlert}와 같은 브리지 방식). fx → monitor 타입 의존이 생기지 않게 common 이벤트로만 통신한다.
 *
 * @param kind     알림 종류 — "REVIEW"(직접매수 검토), "MONTH_START", "MONTH_END"
 * @param currency 통화("USD"·"JPY"), 월초·월말은 "-"
 * @param message  사람이 읽는 한국어 본문
 * @param at       발행 시각
 */
public record FxAlert(String kind, String currency, String message, Instant at) {
}

/**
 * 외화 적립 알림 모듈(VALUNIVO 계획 4-6, D349, 결정 D-28). 규칙 원본은 {@code aiDoc/fx-accumulation-alert.md}.
 *
 * <p>데브가 은행 앱에서 한 외화 목표환율 예약·체결·직접매수를 기록하고({@code fx_reservation}·{@code fx_direct_buy}, V34),
 * 공개 환율(ECOS)로 "직접매수를 검토해 볼 때"를 알린다. <b>이 모듈은 주문을 내지 않고 매수를 추천하지 않는다</b> —
 * 데브가 정한 규칙의 계산만 한다. 거래·전략·리스크·주문 모듈에 의존하지 않고 그쪽도 이 모듈에 의존하지 않는다.
 *
 * <p>판정은 프레임워크·DB를 모르는 {@link com.autostock.fx.FxReviewRule}·{@link com.autostock.fx.FxExposure}에 있고,
 * 일정·ECOS 조회·알림 발송·입력 명령은 바깥 층이다(설계 8장).
 */
@org.springframework.modulith.ApplicationModule(displayName = "fx")
package com.autostock.fx;

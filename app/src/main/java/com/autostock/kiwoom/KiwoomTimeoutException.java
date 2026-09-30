package com.autostock.kiwoom;

/**
 * 키움 REST 호출이 제한 시간({@link KiwoomRestClient#REQUEST_TIMEOUT}) 안에 응답하지 않았다 — <b>결과 불명</b>이다.
 *
 * <p>{@link KiwoomApiException}(응답을 받은 명시 오류)과 구분해야 한다(Phase 0.6, aiDoc/kiwoom-error-codes.md).
 * 주문 TR에서 이 예외를 "브로커 거부"로 번역하면 실제로는 접수된 주문을 REJECTED로 종결하고 대사도 하지 않는다 —
 * 2026-09-22 타임아웃 도입 뒤 생긴 잠복 결함이었다. 주문 경로는 이 예외를 그대로 올려 UNKNOWN + 대사로 확정한다
 * (ARCHITECTURE 6절, aiDoc/http-timeouts.md "주문 TR은 타임아웃이 나도 재시도하지 않는다… UNKNOWN").
 * 호환을 위해 {@link KiwoomApiException}을 상속한다(대시보드 조회 실패는 여전히 502).
 */
public class KiwoomTimeoutException extends KiwoomApiException {

    public KiwoomTimeoutException(String message) {
        super(message);
    }
}

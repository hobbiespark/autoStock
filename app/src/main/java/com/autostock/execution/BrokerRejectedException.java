package com.autostock.execution;

/**
 * 브로커가 <b>명시적으로 응답을 주고 거부</b>한 경우 (운영 1일차 ②).
 *
 * <p>타임아웃/네트워크 오류와 구분하는 것이 존재 이유다: 응답 자체가 왔다는 것은
 * "주문이 브로커에 도달했고, 브로커가 받아들이지 않았다"가 확정됐다는 뜻이므로
 * UNKNOWN(결과 불명)이 아니라 REJECTED(종결)로 분류할 수 있다 — 불필요한
 * Reconciliation 경고를 만들지 않는다.
 *
 * <p>실측 근거(2026-09-11, mockapi): RC4027(지정가 가격제한폭 밖),
 * 800033(모의투자 매도가능수량 부족) 모두 return_code≠0의 명시 거부 응답.
 * KiwoomBrokerAdapter가 KiwoomApiException(API 계층 오류 = 응답 수신 확정)을
 * 이 예외로 번역한다 — trading 모듈은 kiwoom 패키지를 모른 채 이 타입만 본다(Hexagonal).
 */
public class BrokerRejectedException extends RuntimeException {

    public BrokerRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}

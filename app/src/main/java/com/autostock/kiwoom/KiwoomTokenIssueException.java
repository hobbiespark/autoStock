package com.autostock.kiwoom;

/**
 * 접근토큰 발급(au10001) 실패 — {@link TokenManager}가 던진다(Phase 0.6).
 *
 * <p>인증 계열 코드면 {@link TokenManager}가 이미 {@code BrokerAuthFailure}를 발행했으므로, 이 예외가 REST 호출을
 * 거쳐 올라와도 {@link KiwoomRestClient}는 같은 알림을 다시 발행하지 않는다(중복 방지용 구분 타입).
 * 기존 처리와 같게 {@link KiwoomApiException}을 상속한다.
 */
public class KiwoomTokenIssueException extends KiwoomApiException {

    public KiwoomTokenIssueException(String message) {
        super(message);
    }
}

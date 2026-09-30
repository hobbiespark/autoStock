package com.autostock.monitor;

import org.springframework.http.HttpStatus;

/**
 * API 오류 코드 — 응답 ProblemDetail의 {@code code} 확장 필드. 상수 이름은 클라이언트 계약이라 바꾸지 않는다
 * (문구는 바꿔도 된다, §10.2.7).
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식을 읽을 수 없습니다"),
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "요청 파라미터가 올바르지 않습니다"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않는 메서드입니다"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다"),
    CONFLICT(HttpStatus.CONFLICT, "현재 상태와 충돌합니다"),
    UPSTREAM_FAILURE(HttpStatus.BAD_GATEWAY, "외부 연동 오류입니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류입니다");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    /** 프레임워크 예외처럼 코드를 직접 지정하지 않은 오류의 상태 코드별 기본값. */
    static ErrorCode fromStatus(int status) {
        return switch (status) {
            case 400 -> MALFORMED_REQUEST;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 409 -> CONFLICT;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 502 -> UPSTREAM_FAILURE;
            default -> status >= 500 ? INTERNAL_ERROR : MALFORMED_REQUEST;
        };
    }
}

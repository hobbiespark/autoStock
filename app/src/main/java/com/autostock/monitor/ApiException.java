package com.autostock.monitor;

import java.util.Map;

/**
 * 컨트롤러가 던지는 예상된 업무 오류(없음·상태 충돌·잘못된 파라미터). {@link ApiExceptionHandler}가
 * ProblemDetail로 바꾼다. detail은 클라이언트에 그대로 나가므로 내부 정보를 넣지 않는다.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final transient Map<String, Object> properties;

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, Map.of());
    }

    /** @param properties ProblemDetail 확장 필드(예: 현재 상태) */
    public ApiException(ErrorCode code, String detail, Map<String, Object> properties) {
        super(detail);
        this.code = code;
        this.properties = Map.copyOf(properties);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}

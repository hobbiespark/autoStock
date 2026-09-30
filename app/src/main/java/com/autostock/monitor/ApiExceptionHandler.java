package com.autostock.monitor;

import com.autostock.common.util.SecretMasking;
import com.autostock.kiwoom.KiwoomApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;

/**
 * API 오류 응답을 한 곳에서 만든다 — RFC 9457 ProblemDetail({@code application/problem+json}) + 기계용
 * {@code code}(ErrorCode). Spring MVC 표준 예외는 상위 클래스가 ProblemDetail로 만들고 여기서 code만 붙인다.
 * 내부 원인(예외 메시지·스택·SQL)은 서버 로그에만 남긴다(§5.5, §6).
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException e) {
        ProblemDetail body = problem(e.code(), e.getMessage());
        e.properties().forEach(body::setProperty);
        return response(body);
    }

    /** 배치와 수동 입력 등 동시 갱신 충돌 — 최신 값을 다시 읽고 재시도하라는 뜻. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException e) {
        log.info("동시 갱신 충돌로 요청 거절(409): {}", e.getClass().getSimpleName());
        return response(problem(ErrorCode.CONFLICT, "다른 작업이 먼저 갱신했습니다. 최신 값을 다시 불러와 입력하세요."));
    }

    /** 하위 시스템(브로커)이 실패한 것은 호출자 잘못이 아니므로 502(§6). 응답 본문은 로그에만 남긴다. */
    @ExceptionHandler(KiwoomApiException.class)
    ResponseEntity<ProblemDetail> handleUpstream(KiwoomApiException e) {
        log.warn("브로커 연동 실패(502): {}", SecretMasking.mask(e.getMessage()));
        return response(problem(ErrorCode.UPSTREAM_FAILURE, "브로커 연동에 실패했습니다. 잠시 후 다시 시도하세요."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외(500)", SecretMasking.sanitizeForLogging(e));
        return response(problem(ErrorCode.INTERNAL_ERROR, "요청을 처리하지 못했습니다."));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail body = problem(ErrorCode.VALIDATION_FAILED, "입력값을 확인하세요.");
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(),
                        "message", f.getDefaultMessage() == null ? "올바르지 않은 값" : f.getDefaultMessage()))
                .toList();
        body.setProperty("errors", errors); // 거부된 값 자체는 싣지 않는다(개인정보·주입 문자열 반사 방지)
        return handleExceptionInternal(e, body, headers, status, request);
    }

    /** 상위 클래스가 만든 ProblemDetail에 code가 없으면 상태 코드로 채운다. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        if (body instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
            problem.setProperty("code", ErrorCode.fromStatus(status.value()).name());
        }
        return super.handleExceptionInternal(e, body, headers, status, request);
    }

    private static ProblemDetail problem(ErrorCode code, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(code.status(), detail);
        body.setTitle(code.title());
        body.setProperty("code", code.name());
        return body;
    }

    private static ResponseEntity<ProblemDetail> response(ProblemDetail body) {
        return ResponseEntity.status(body.getStatus()).body(body);
    }
}

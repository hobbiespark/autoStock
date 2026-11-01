package com.autostock.monitor;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 요청마다 추적 ID를 정한다(VALUNIVO 공통 오류 형식, 4-2 S4). 형식은 W3C Trace Context의 trace-id(16진수 32자리)다.
 * 들어온 {@code traceparent}가 올바르면 그 trace-id를 잇고(플랫폼이 부른 요청), 없거나 틀리면 새로 만든다.
 * 응답 헤더 {@code X-Trace-Id}와 오류 본문 {@code trace_id}에 같은 값을 넣는다. 요청 내용과 무관한 무작위 값이라 비밀이 아니다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIds extends OncePerRequestFilter {

    public static final String ATTRIBUTE = "autostock.trace_id";
    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "trace_id";
    private static final Pattern TRACEPARENT = Pattern.compile("00-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}");
    private static final String ZERO = "0".repeat(32);
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = from(request.getHeader("traceparent"));
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id);
        MDC.put(MDC_KEY, id); // 요청 동안 로그에도 같은 ID. 스레드 재사용에 대비해 끝나면 지운다
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    static String from(String traceparent) {
        if (traceparent != null && traceparent.length() <= 64) {
            var match = TRACEPARENT.matcher(traceparent.strip());
            if (match.matches() && !match.group(1).equals(ZERO)) {
                return match.group(1);
            }
        }
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** 지금 요청의 추적 ID. 요청 밖(시험·배치)이면 비어 있다. */
    static Optional<String> current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return Optional.empty();
        }
        return attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof String id
                ? Optional.of(id) : Optional.empty();
    }
}

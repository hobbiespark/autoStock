package com.autostock.config.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.OutputStreamAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SecretMaskingConverter를 실제 로그백 파이프라인(로거 → 어펜더 → 인코더 → 출력)에 꽂아
 * "위험 문자열을 로그로 찍으면 실제 어펜더 출력에서 마스킹되는가"를 검증한다 — 단위 테스트가
 * {@link com.autostock.common.util.SecretMasking#mask(String)}를 직접 검증하는 것과 별개로,
 * logback-spring.xml의 conversionRule 배선(%mask(%m)%n%maskedEx)이 실제로 동작하는지
 * 확인하는 통합 확인이다(작업 지시 "검증·커밋" 절).
 */
class SecretMaskingConverterTest {

    private LoggerContext context;
    private ByteArrayOutputStream out;
    private OutputStreamAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        context = (LoggerContext) LoggerFactory.getILoggerFactory();

        // conversionRule 등록 — logback-spring.xml의
        // <conversionRule conversionWord="mask" converterClass="...SecretMaskingConverter"/>와 동일 배선.
        @SuppressWarnings("unchecked")
        Map<String, String> ruleRegistry =
                (Map<String, String>) context.getObject(CoreConstants.PATTERN_RULE_REGISTRY);
        if (ruleRegistry == null) {
            ruleRegistry = new HashMap<>();
            context.putObject(CoreConstants.PATTERN_RULE_REGISTRY, ruleRegistry);
        }
        ruleRegistry.put("mask", SecretMaskingConverter.class.getName());
        ruleRegistry.put("maskedEx", ThrowableMaskingConverter.class.getName());
    }

    @AfterEach
    void tearDown() {
        if (logger != null && appender != null) {
            logger.detachAppender(appender);
        }
    }

    private String logAndCapture(String message, Throwable throwable) {
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%mask(%msg)%n%maskedEx");
        encoder.start();

        out = new ByteArrayOutputStream();
        appender = new OutputStreamAppender<>();
        appender.setContext(context);
        appender.setOutputStream(out);
        appender.setEncoder(encoder);
        appender.start();

        logger = context.getLogger(SecretMaskingConverterTest.class.getName() + ".case" + System.nanoTime());
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        logger.setAdditive(false);

        logger.info(message, throwable);
        appender.stop();

        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void 로그_메시지의_DART_키가_어펜더_출력에서_마스킹된다() {
        String rendered = logAndCapture(
                "DART list.json 조회 실패: crtfc_key=abcdef0123456789 응답 오류", null);

        assertFalse(rendered.contains("abcdef0123456789"));
        assertTrue(rendered.contains("crtfc_key=***"));
    }

    @Test
    void 스택트레이스에_담긴_텔레그램_봇_토큰이_마스킹된다() {
        RuntimeException ex = new RuntimeException(
                "404 Not Found from GET https://api.telegram.org/bot123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw/sendMessage");

        String rendered = logAndCapture("텔레그램 알림 발송 실패", ex);

        assertFalse(rendered.contains("AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw"));
        assertTrue(rendered.contains("bot***"));
    }

    @Test
    void 정상_로그_메시지는_그대로_출력된다() {
        String rendered = logAndCapture(
                "주문 발행: clientOrderId=20260911-C3-005930-BUY-001, symbol=005930", null);

        assertTrue(rendered.contains("20260911-C3-005930-BUY-001"));
        assertTrue(rendered.contains("005930"));
    }
}

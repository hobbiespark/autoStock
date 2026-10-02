package com.autostock.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DB 통합 테스트를 돌릴 PostgreSQL이 없으면 건너뛴다(결정 D-18, {@link PostgresTestDatabase}).
 *
 * <p>스프링 컨텍스트를 띄우기 전에 판정한다 — 컨텍스트 기동 실패로 "실패"가 찍히지 않게 한다.
 * {@code AUTOSTOCK_TEST_DB_REQUIRED=true}(CI)면 건너뛰지 않고 예외로 실패시킨다.
 */
public class PostgresAvailableCondition implements ExecutionCondition {

    private static final Logger log = LoggerFactory.getLogger(PostgresAvailableCondition.class);
    private static boolean warned;

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (PostgresTestDatabase.available()) {
            return ConditionEvaluationResult.enabled("DB 통합 테스트 PostgreSQL 있음");
        }
        String reason = "DB 통합 테스트 건너뜀 — Docker도 AUTOSTOCK_TEST_DB_URL도 없다(D-18: 운영 이미지로만 검증)";
        if (PostgresTestDatabase.required()) {
            throw new IllegalStateException(reason + " — AUTOSTOCK_TEST_DB_REQUIRED=true라 실패로 처리한다");
        }
        synchronized (PostgresAvailableCondition.class) {
            if (!warned) {
                warned = true;
                log.warn(reason);
            }
        }
        return ConditionEvaluationResult.disabled(reason);
    }
}

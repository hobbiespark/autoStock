package com.autostock.kiwoom;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 헬스 {@code kiwoomAuth} — 마지막 접근토큰 발급 결과 (실행 계획 1.7, aiDoc/observability.md).
 *
 * <p>성공이면 UP(발급 시각·만료 시각), 실패면 DOWN(시도 시각·사유 코드 — 8030 같은 인증 코드면 허용 IP·App Key 확인),
 * 아직 발급 전이면 UNKNOWN. 토큰 값은 싣지 않는다.
 */
@Component
class KiwoomAuthHealthIndicator implements HealthIndicator {

    private final TokenManager tokenManager;

    KiwoomAuthHealthIndicator(TokenManager tokenManager) {
        this.tokenManager = tokenManager;
    }

    @Override
    public Health health() {
        return tokenManager.lastIssueStatus()
                .map(status -> status.success()
                        ? Health.up()
                                .withDetail("lastIssuedAt", status.at().toString())
                                .withDetail("expiresAt", status.expiresAt().toString())
                                .build()
                        : Health.down()
                                .withDetail("lastAttemptAt", status.at().toString())
                                .withDetail("failure", status.failure())
                                .build())
                .orElseGet(() -> Health.unknown().withDetail("lastIssuedAt", "아직 발급 전").build());
    }
}

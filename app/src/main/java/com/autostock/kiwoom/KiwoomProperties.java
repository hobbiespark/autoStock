package com.autostock.kiwoom;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 실전/모의 도메인 혼동 방지: base URL은 프로필(paper/live)로만 전환 (PLAN 3절).
 * 앱키/시크릿은 환경변수 주입 — 커밋 금지.
 *
 * <p>키 값은 앞뒤 공백·개행을 제거해 보관한다. GitHub 시크릿이나 .env에 값을 붙여 넣을 때 끝에 개행이 딸려 오면
 * 키움이 8001(App Key/Secret 검증 실패)로 거부하는데, 로그에는 키가 마스킹돼 원인을 알 수 없다
 * (2026-09-18 CI KiwoomSmokeIT 실패 조사 중 방어 추가). {@code null}은 그대로 두어 {@code @NotBlank} 검증에 걸리게 한다.
 */
@Validated
@ConfigurationProperties(prefix = "kiwoom")
public record KiwoomProperties(
        @NotBlank String restBaseUrl,
        @NotBlank String wsUrl,
        @NotBlank String appKey,
        @NotBlank String appSecret
) {
    public KiwoomProperties {
        restBaseUrl = strip(restBaseUrl);
        wsUrl = strip(wsUrl);
        appKey = strip(appKey);
        appSecret = strip(appSecret);
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }
}

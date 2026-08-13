package com.autostock.kiwoom;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 실전/모의 도메인 혼동 방지: base URL은 프로필(paper/live)로만 전환 (PLAN 3절).
 * 앱키/시크릿은 환경변수 주입 — 커밋 금지.
 */
@Validated
@ConfigurationProperties(prefix = "kiwoom")
public record KiwoomProperties(
        @NotBlank String restBaseUrl,
        @NotBlank String wsUrl,
        @NotBlank String appKey,
        @NotBlank String appSecret
) {
}

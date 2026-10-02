package com.autostock.trading;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 잔고 ↔ 장부 주기 대사 설정 (실행 계획 1.5, 규약-6 — aiDoc/position-reconcile.md).
 *
 * @param enabled 켜기(기본 false — 장외 검증 후 true로 바꾼다. 켜도 LIVE·장중 세션에서만 돈다)
 */
@ConfigurationProperties(prefix = "trading.position-reconcile")
public record PositionReconcileProperties(@DefaultValue("false") boolean enabled) {
}

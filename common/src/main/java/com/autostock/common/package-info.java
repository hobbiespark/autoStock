/**
 * 공용 계약(이벤트 스키마) 모듈 — 모든 모듈이 소비하는 OPEN 모듈.
 * 여기 있는 타입이 곧 모듈 간 통신 계약이다. 변경 시 스키마 버전 규칙 준수 (PLAN ADR-2).
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "common-contracts",
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.autostock.common;

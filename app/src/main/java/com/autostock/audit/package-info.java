/**
 * 감사 추적 모듈: 모든 도메인 이벤트를 append-only 이벤트 스토어에 영속화.
 * 백테스트 리플레이의 원천 (PLAN ADR-2). 삭제/수정 금지.
 */
@org.springframework.modulith.ApplicationModule(displayName = "audit")
package com.autostock.audit;

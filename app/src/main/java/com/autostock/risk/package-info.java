/**
 * 리스크 모듈 — 주문의 유일한 관문 (PLAN 4절 불변 원칙).
 * Signal은 여기서 사이징·한도·킬스위치 검사를 통과해야만 OrderRequest가 된다.
 */
@org.springframework.modulith.ApplicationModule(displayName = "risk")
package com.autostock.risk;

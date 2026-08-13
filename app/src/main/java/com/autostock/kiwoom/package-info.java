/**
 * 키움 REST/WS 공유 인프라 모듈 — marketdata·execution이 사용.
 * 책임: 토큰 관리, TR별 rate limit, 실전/모의 도메인 토글.
 */
@org.springframework.modulith.ApplicationModule(displayName = "kiwoom-client")
package com.autostock.kiwoom;

/**
 * 거시 인텔리전스 모듈 (Phase 5): ECOS/FRED/DART 배치 수집 → MacroIndicator 발행.
 * 장애가 매매 루프를 막지 않도록 격리 — 수집 실패 시 보수 모드 신호만.
 */
@org.springframework.modulith.ApplicationModule(displayName = "macro-intel")
package com.autostock.macrointel;

package com.autostock;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * 모듈 경계 강제 테스트 (PLAN ADR-1).
 * 모듈 간 직접 참조가 생기면 빌드가 실패한다 — 이 테스트를 우회하지 말 것.
 */
class ModularityTests {

    @Test
    void verifyModuleBoundaries() {
        ApplicationModules modules = ApplicationModules.of(AutostockApplication.class);
        modules.forEach(System.out::println);
        modules.verify();
    }
}

package com.autostock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.modulith.Modulithic;

/**
 * autoStock — 논리적 MSA, 물리적 모놀리스 (PLAN.md ADR-1).
 * 모듈 간 통신은 Spring 이벤트만 허용. 경계는 ModularityTests로 강제.
 *
 * <p>sharedModules: 업무 이벤트 흐름이 아니라 여러 모듈이 공용으로 참조하는 순수 인프라
 * 배선이라 예외적으로 직접 참조를 허용한다 — kiwoom(REST/WS 클라이언트), config(CacheConfig의
 * 캐시 이름 상수를 marketdata가 직접 참조, PLAN ADR-5).
 */
@Modulithic(systemName = "autostock", sharedModules = {"kiwoom", "config"})
@SpringBootApplication
@ConfigurationPropertiesScan
public class AutostockApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutostockApplication.class, args);
    }
}

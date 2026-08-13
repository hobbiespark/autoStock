package com.autostock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.modulith.Modulithic;

/**
 * autoStock — 논리적 MSA, 물리적 모놀리스 (PLAN.md ADR-1).
 * 모듈 간 통신은 Spring 이벤트만 허용. 경계는 ModularityTests로 강제.
 */
@Modulithic(systemName = "autostock", sharedModules = "kiwoom")
@SpringBootApplication
@ConfigurationPropertiesScan
public class AutostockApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutostockApplication.class, args);
    }
}

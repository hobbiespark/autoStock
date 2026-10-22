package com.autostock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.modulith.Modulithic;

import java.nio.file.Path;

/**
 * autoStock — 논리적 MSA, 물리적 모놀리스 (PLAN.md ADR-1).
 * 모듈 간 통신은 Spring 이벤트만 허용. 경계는 ModularityTests로 강제.
 *
 * <p>sharedModules: 업무 이벤트 흐름이 아니라 여러 모듈이 공용으로 참조하는 순수 인프라
 * 배선이라 예외적으로 직접 참조를 허용한다 — kiwoom(REST/WS 클라이언트), config(CacheConfig의
 * 캐시 이름 상수를 market이 직접 참조, PLAN ADR-5).
 */
@Modulithic(systemName = "autostock", sharedModules = {"kiwoom", "config"})
@SpringBootApplication
@ConfigurationPropertiesScan
public class AutostockApplication {

    public static void main(String[] args) {
        // 두 번 뜨지 않게 — 10/15 07:53 17초 간격 이중 기동(SingleInstanceGuard). Spring·DB를 건드리기 전에 막는다.
        Path lockFile = Path.of(SingleInstanceGuard.LOCK_FILE);
        if (!SingleInstanceGuard.disabled(args) && !SingleInstanceGuard.acquire(lockFile)) {
            System.err.println("[X] autoStock이 이미 실행 중이다(" + lockFile.toAbsolutePath() + " 잠김) — 두 번째 실행은 멈춘다. "
                    + "켜져 있는 창을 확인하고, 다시 켜려면 먼저 scripts\\stop_autostock.bat으로 끈다.");
            System.exit(1);
        }
        SpringApplication.run(AutostockApplication.class, args);
    }
}

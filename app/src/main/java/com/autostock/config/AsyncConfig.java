package com.autostock.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * {@code @Async} 활성화 (PLAN ADR-5).
 *
 * <p>별도 Executor 빈을 두지 않는다 — {@code spring.threads.virtual.enabled=true}일 때
 * Spring Boot가 가상 스레드 기반 {@code SimpleAsyncTaskExecutor}를 자동 구성하고
 * {@code taskExecutor} 별칭으로 등록하므로, {@code @Async}가 기본 탐색 규칙으로 이를
 * 그대로 사용한다. 대상: audit/EventAuditListener(감사 기록을 매매 핫패스에서 분리).
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}

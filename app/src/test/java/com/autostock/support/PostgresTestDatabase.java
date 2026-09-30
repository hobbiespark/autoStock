package com.autostock.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;

/**
 * DB 통합 테스트용 PostgreSQL — 테스트 JVM 하나에 한 번만 띄워 모든 DB 테스트가 같이 쓴다.
 *
 * <p>사용자 결정(2026-09-30): <b>운영 정확도 우선, Docker가 없을 때도 돈다.</b>
 * <ol>
 *   <li>Docker가 있으면 Testcontainers로 운영과 같은 이미지({@link #IMAGE}, infra/docker-compose.yml)를 띄운다.</li>
 *   <li>없으면(작업 환경·Docker 미기동 PC) zonky 내장 PostgreSQL을 띄운다. 바이너리 버전은 build.gradle에서 운영과 같은
 *       16.15로 고정했다. 서버 시간대는 이미지 기본값과 같은 UTC로 맞춘다.</li>
 * </ol>
 * 어느 쪽을 썼는지는 로그 한 줄로 남긴다 — 결과를 읽을 때 "Docker로 검증됐는가"를 구분하기 위해서다.
 */
public final class PostgresTestDatabase {

    /** 운영 이미지 — infra/docker-compose.yml과 같아야 한다. */
    public static final String IMAGE = "postgres:16-alpine";

    private static final Logger log = LoggerFactory.getLogger(PostgresTestDatabase.class);

    private static Connection connection;

    private PostgresTestDatabase() {
    }

    /** Spring 테스트의 {@code @DynamicPropertySource}에서 부른다. */
    public static void register(DynamicPropertyRegistry registry) {
        Connection db = connection();
        registry.add("spring.datasource.url", db::url);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
    }

    /** 이번 테스트 실행이 어느 DB로 검증됐는지 — "docker postgres:16-alpine" 또는 "embedded 16.15". */
    public static String kind() {
        return connection().kind();
    }

    private static synchronized Connection connection() {
        if (connection == null) {
            connection = dockerAvailable() ? startContainer() : startEmbedded();
            log.info("DB 통합 테스트 PostgreSQL: {} ({})", connection.kind(), connection.url());
        }
        return connection;
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable e) {   // Docker 클라이언트 초기화 실패도 "없음"으로 본다
            return false;
        }
    }

    @SuppressWarnings("resource")   // 테스트 JVM이 끝날 때 Testcontainers(Ryuk)가 정리한다
    private static Connection startContainer() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(DockerImageName.parse(IMAGE));
        container.start();
        return new Connection("docker " + IMAGE, container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    private static Connection startEmbedded() {
        try {
            EmbeddedPostgres postgres = EmbeddedPostgres.builder().setServerConfig("timezone", "UTC").start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    postgres.close();
                } catch (IOException ignored) {
                    // 테스트 JVM 종료 중 — 남길 곳이 없다
                }
            }));
            return new Connection("embedded 16.15 (Docker 없음)", postgres.getJdbcUrl("postgres", "postgres"),
                    "postgres", "postgres");
        } catch (IOException e) {
            throw new IllegalStateException("내장 PostgreSQL 기동 실패", e);
        }
    }

    private record Connection(String kind, String url, String username, String password) {
    }
}

package com.autostock.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * DB 통합 테스트용 PostgreSQL — 테스트 JVM 하나에 한 번만 띄워 모든 DB 테스트가 같이 쓴다.
 *
 * <p>결정 D-18(2026-10-02, aiDoc/db-switch-timescale.md): <b>운영과 같은 이미지로만 검증한다.</b>
 * 운영 DB를 TimescaleDB HA 이미지(PostgreSQL 17)로 바꾸면서 마이그레이션이 확장 기능을 쓰게 됐다. 확장을 올릴 수 없는
 * 내장 PostgreSQL(zonky, 2026-09-30 결정 "Docker가 없을 때도 돈다")은 뺐다.
 * <ol>
 *   <li>환경변수 {@code AUTOSTOCK_TEST_DB_URL}이 있으면 그 DB를 쓴다(Docker 없는 작업 환경 — 같은 이미지·같은 기동 설정의
 *       빈 DB여야 한다. 사용자·암호는 {@code AUTOSTOCK_TEST_DB_USER}·{@code AUTOSTOCK_TEST_DB_PASSWORD}, 기본 autostock).</li>
 *   <li>Docker가 있으면 Testcontainers로 운영과 같은 이미지({@link #IMAGE})를 운영과 같은 사전 적재 설정으로 띄운다.</li>
 *   <li>둘 다 없으면 DB 테스트를 건너뛴다({@link PostgresAvailableCondition}). {@code AUTOSTOCK_TEST_DB_REQUIRED=true}(CI)면
 *       건너뛰지 않고 실패시킨다 — Docker 문제로 DB 검증이 조용히 빠지는 것을 막는다.</li>
 * </ol>
 * 어느 쪽을 썼는지는 로그 한 줄로 남긴다 — 결과를 읽을 때 "운영 이미지로 검증됐는가"를 구분하기 위해서다.
 */
public final class PostgresTestDatabase {

    /**
     * 운영 이미지 — infra/docker-compose(.timescale).yml의 {@code timescale/timescaledb-ha:pg17.11-ts2.30.2}와 같은 다이제스트.
     * Testcontainers는 "이름:태그@다이제스트"를 받지 않아 다이제스트만 적는다.
     */
    public static final String IMAGE =
            "timescale/timescaledb-ha@sha256:2fcc39a5d4c8a65f58691ef92c7819df5773db72397b2dd8493f114659b519c2";

    /** 운영과 같은 사전 적재 라이브러리 — compose의 {@code command}와 같아야 한다. */
    static final String PRELOAD = "timescaledb,pg_textsearch,pg_stat_statements,pg_prewarm";

    private static final Logger log = LoggerFactory.getLogger(PostgresTestDatabase.class);

    private static Connection connection;
    private static Boolean dockerAvailable;

    private PostgresTestDatabase() {
    }

    /** Spring 테스트의 {@code @DynamicPropertySource}에서 부른다. */
    public static void register(DynamicPropertyRegistry registry) {
        Connection db = connection();
        registry.add("spring.datasource.url", db::url);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
    }

    /** 이번 테스트 실행이 어느 DB로 검증됐는지 — "docker timescale/timescaledb-ha@…" 또는 "external jdbc:…". */
    public static String kind() {
        return connection().kind();
    }

    /** DB 테스트를 돌릴 수 있는가 — 외부 주소가 있거나 Docker가 있다. 컨테이너는 띄우지 않는다. */
    public static synchronized boolean available() {
        return externalUrl() != null || dockerAvailable();
    }

    /** CI처럼 DB 테스트가 빠지면 안 되는 환경인가 — {@code AUTOSTOCK_TEST_DB_REQUIRED=true}. */
    static boolean required() {
        return "true".equalsIgnoreCase(System.getenv("AUTOSTOCK_TEST_DB_REQUIRED"));
    }

    private static String externalUrl() {
        String url = System.getenv("AUTOSTOCK_TEST_DB_URL");
        return url == null || url.isBlank() ? null : url;
    }

    private static synchronized Connection connection() {
        if (connection == null) {
            String url = externalUrl();
            if (url != null) {
                connection = new Connection("external " + url, url,
                        System.getenv().getOrDefault("AUTOSTOCK_TEST_DB_USER", "autostock"),
                        System.getenv().getOrDefault("AUTOSTOCK_TEST_DB_PASSWORD", "autostock"));
            } else if (dockerAvailable()) {
                connection = startContainer();
            } else {
                // PostgresAvailableCondition이 먼저 걸러 여기까지 오지 않는다 — 직접 부른 경우를 위한 안내
                throw new IllegalStateException("DB 통합 테스트용 PostgreSQL 없음 — Docker를 켜거나 AUTOSTOCK_TEST_DB_URL을 준다");
            }
            log.info("DB 통합 테스트 PostgreSQL: {} ({})", connection.kind(), connection.url());
        }
        return connection;
    }

    private static boolean dockerAvailable() {
        if (dockerAvailable == null) {
            try {
                dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
            } catch (Throwable e) {   // Docker 클라이언트 초기화 실패도 "없음"으로 본다
                dockerAvailable = false;
            }
        }
        return dockerAvailable;
    }

    @SuppressWarnings("resource")   // 테스트 JVM이 끝날 때 Testcontainers(Ryuk)가 정리한다
    private static Connection startContainer() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(
                DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                // 운영과 같은 사전 적재 + 테스트 속도용 fsync=off(Testcontainers 기본값 유지)
                .withCommand("postgres", "-c", "fsync=off", "-c", "shared_preload_libraries=" + PRELOAD)
                .withEnv("TIMESCALEDB_TELEMETRY", "off")
                // 튜닝 기준을 고정한다 — 비우면 Docker VM 메모리의 25%를 잡는다. 연결 수는 25 이상이어야 한다(tune 제약)
                .withEnv("TS_TUNE_MEMORY", "1GB")
                .withEnv("TS_TUNE_MAX_CONNS", "100");
        container.start();
        return new Connection("docker " + IMAGE, container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    private record Connection(String kind, String url, String username, String password) {
    }
}

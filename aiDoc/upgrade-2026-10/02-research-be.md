# 02. BE 관점 조사 — 플랫폼·프레임워크 최신 현황 (2026-10-01)

> 문서 묶음: [00-README](00-README.md) · [01 현황](01-current-state.md) · 조사 [02 BE](02-research-be.md) · [03 FE](03-research-fe.md) · [04 디자인](04-research-design.md) · [05 기획](05-research-planning.md) · [06 인프라](06-research-infra.md) · [07 주식거래①시장·제도](07-research-trading-market.md) · [08 주식거래②전략·LLM](08-research-trading-strategy.md) · 감사 [09 BE](09-audit-be.md) · [10 FE](10-audit-fe.md) · **[11 실행 계획](11-execution-plan.md)** · [12 결정 목록](12-decisions.md)


- 조사일: 2026-10-01 (20분 타임박스 마무리판)
- 대상: Java 21(Temurin) · Spring Boot 3.5.16 · Spring Modulith 1.4.12 · Gradle 8.14.2 · Hibernate(Boot BOM 6.6.x) · Flyway · PostgreSQL 16 · WebClient(.block()) · spring-websocket · Resilience4j 2.2.0 · Caffeine · Micrometer/Actuator · springdoc 2.8.9 · JNA 5.14 · Testcontainers + zonky PG 16.15 · ArchUnit 1.4.2 · JUnit 5.11 · 가상 스레드 · Windows PC 24h 단일 JVM · Modulith event_publication 사용 · 외부화 미도입
- 표기 원칙: 출처로 확인한 것만 버전·날짜 기재. 확인 못 한 것은 **미확인**. 시각화 없음.

---

## 보강 확인·정정 (2026-10-01 01:10~01:35, 1차 자료 재확인)

| 항목 | 결과 | 근거 |
|---|---|---|
| Boot 3.5 OSS 종료 | 2026-06-30 종료 재확인(최신 3.5.16, 상용 2032-06-30). 4.0 OSS 2026-12-31, 4.1 OSS 2027-07-31 | [endoflife.date API](https://endoflife.date/api/spring-boot.json) |
| Boot 3.5.16 요구사항 | Java 17~**25** 호환, Framework 6.2.19+, **Gradle 7.6.4+ 또는 8.4+**(9.x 없음), Tomcat 10.1 | [3.5 system-requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html) |
| Boot 4.1.1 요구사항 | Java 17~**26** 호환, Framework 7.0.9+, **Gradle 8.14+ 및 9.x**, Tomcat 11.0(Servlet 6.1) | [system-requirements](https://docs.spring.io/spring-boot/system-requirements.html) |
| Gradle ↔ JDK | Java 25 툴체인·실행 = **Gradle 9.1.0+**, Java 24 = 8.14+, Java 26 = 9.4.0+ | [Gradle 호환표](https://docs.gradle.org/current/userguide/compatibility.html) |
| **이관 순서 정정** | 초안 "JDK 25 → Gradle 9 → Boot 4.1" 불가 → **Boot 4.1(Gradle 8.14) → Gradle 9.1+ → JDK 25** | 위 3행 |
| Modulith 스키마 | 현행 `event_publication` = 기존 6컬럼 + `status`, `completion_attempts`, `last_resubmission_date`. 우리 V1은 구형 → 2.x 이관 전 컬럼 추가 필요 | [Modulith 부록](https://docs.spring.io/spring-modulith/reference/appendix.html), `V1__event_store.sql:15-24` |
| Boot 4 테스트 | JUnit 6 표준(대부분 드롭인), Testcontainers 2.0 모듈명 `testcontainers-postgresql`, `@MockBean` 제거(이 리포 사용 0건) | [rieckpil 정리](https://rieckpil.de/whats-new-for-testing-in-spring-boot-4-0-and-spring-framework-7/), grep |
| Boot 4 이관 접점(코드 grep) | `config/JacksonConfig`(Jackson 2 `SimpleModule`·`JsonSerializer`), `ipo/DartClient`·`macrointel/MajorDisclosureDartClient`(`ClientHttpConnectorBuilder/Settings`), yml `spring.http.reactiveclient.*` | 코드 확인 |
| Resilience4j | 코어 모듈만 사용 → Boot 4 결합 없음 | `app/build.gradle` |

## 0. 핵심 결론 (먼저 읽을 것)

1. **Spring Boot 3.5 OSS 지원은 2026-06-30에 이미 종료됨.** 3.5.16(2026-06-25)이 마지막 OSS 패치이며, 이후 보안 패치는 상용(Tanzu) 전용. 현재 시스템은 **OSS 기준 EOL 상태**로 운영 중. (출처: endoflife.date/spring-boot, spring.io 지원 정책)
2. **Spring Boot 4.0**은 2025-11 GA, 최신 **4.0.8(2026-08-20)**, OSS 지원 종료 **2026-12-31**. **4.1**은 2026-06-30 GA, 최신 **4.1.1(2026-08-20)**, OSS 지원 **2027-07-31**까지. 4.2는 M2 단계(2026-09-25). → 지금 이관한다면 **4.1.x**가 목표여야 함(4.0은 3개월 뒤 EOL).
3. **Spring Modulith**: 1.4 라인 최신 **1.4.13(2026-08-26)**, 2.0 GA는 2025-11-21(최신 2.0.8), **2.1 GA 2026-06-11(최신 2.1.1, Boot 4.1 기준선)**, 2.2 M1/M2 진행 중. 1.4는 Boot 3.5 종속이므로 Boot 4 이관 시 **Modulith 2.1**로 동반 이관.
4. **JDK 25 LTS**는 2025-09-16 GA(Temurin 25: 2025-09-22, 지원 2031-09까지). JDK 21→25는 언어·런타임 변화가 크지 않아 이관 난이도 낮음. Boot 3.5.16은 **Java 25까지 공식 호환**(보강 확인). 단 JDK 25 **툴체인**은 Gradle 9.1.0+가 필요하고 Boot 3.5는 Gradle 7.6.4+/8.4+만 지원 → **Boot 3.5 상태에서는 JDK 25 빌드 불가**(보강 정정).
5. **PostgreSQL 16**은 2028-11-09까지 지원(급하지 않음). 17(2024-09), 18(2025-09-25) 출시. 16.15는 2026-08-10 최신 패치와 일치.
6. 권고 순서(보강 정정 반영): **(a) 즉시** 3.5.16 고정 + 이관 선행 정리(속성·스키마 Expand, Gradle 8.14.x 유지) → **(b) 6개월 내** **Boot 4.1 + Modulith 2.1 + Hibernate 7 + Jackson 3(Gradle 8.14 유지) → Gradle 9.1+ → JDK 25** 순 → **(c)** 4.0.x로의 이관, Kafka 외부화, PG 18 성급한 업그레이드는 하지 말 것. RestClient 전환은 실행 계획에서 보류로 정리(11-execution-plan 충돌 해소표).

---

## 1. Spring Boot

### 사실·버전·날짜
| 항목 | 값 | 출처 |
|---|---|---|
| 3.5 GA / 최신 | 2025-05-31 / **3.5.16 (2026-06-25)** | endoflife.date/spring-boot; GitHub releases v3.5.16 |
| 3.5 OSS 지원 종료 | **2026-06-30** (상용 2032-06-30) | endoflife.date/spring-boot |
| 4.0 GA / 최신 | 2025-11-30 / **4.0.8 (2026-08-20)** | endoflife.date/spring-boot |
| 4.0 OSS 지원 종료 | **2026-12-31** (상용 2027-12-31) | endoflife.date/spring-boot |
| 4.1 GA / 최신 | 2026-06-30 / **4.1.1 (2026-08-20)** | endoflife.date/spring-boot |
| 4.1 OSS 지원 종료 | 2027-07-31 | endoflife.date/spring-boot |
| 4.2 | 4.2.0-M2 (2026-09-25) — 11월 GA 예정(추정) | spring.io/blog/category/releases |
| 지원 정책 | 마이너 12개월+, 메이저 3년+, 5월/11월 6개월 주기 | github.com/spring-projects/spring-boot/wiki/Supported-Versions |

3.5.16은 의존성 업그레이드만 포함(Spring AMQP 3.2.12, Spring Data 2025.0.13, Spring Integration 6.5.10). 버그 픽스 없음.

### 4.0 주요 변경 (출처: Spring-Boot-4.0-Migration-Guide, Spring-Boot-4.0-Release-Notes 위키)
- 기준선: **Java 17+**(최신 LTS 권장), **Spring Framework 7.0**, **Jakarta EE 11 / Servlet 6.1**, **Hibernate 7.1**, **Jackson 3.0**, Kotlin 2.2+, GraalVM 25+.
- **모듈화된 starter**: `spring-boot-starter-web` → `spring-boot-starter-webmvc`, `-aop` → `-aspectj`, **Flyway는 `spring-boot-starter-flyway` 명시 필요**, `spring-boot-starter-restclient`(RestClient/RestTemplate), `spring-boot-starter-webclient`(WebClient), 기술별 `-test` starter. 과도기용 `spring-boot-starter-classic` / `spring-boot-starter-test-classic` 제공. Undertow 제거.
- **Jackson 3**: groupId `tools.jackson`(annotations 제외), `@JsonComponent`→`@JacksonComponent`, `Jackson2ObjectMapperBuilderCustomizer`→`JsonMapperBuilderCustomizer`, 속성 `spring.jackson.read/write.*`→`spring.jackson.json.read/write.*`. 호환 `spring.jackson.use-jackson2-defaults=true` 또는 deprecated `spring-boot-jackson2` 모듈.
- **Hibernate 7**: `hibernate-jpamodelgen`→`hibernate-processor`.
- **HTTP 클라이언트**: `HttpServiceClient`(@HttpExchange 인터페이스) 자동구성 신설(`@ImportHttpServices`, `spring.http.serviceclient.<group>.*`). 4.0 미이관 가이드에는 없으나 **deprecated-application-properties(4.0)에 명시**: `spring.http.client.*` → `spring.http.clients.*`(`imperative.factory`), **`spring.http.reactiveclient.*` → `spring.http.clients.*`(`reactive.connector`)**. 즉 `spring.http.reactiveclient.*`는 4.0에서 **deprecated(호환 유지)**, 4.x 후속에서 제거 예상 → 이관 시 `spring.http.clients.*`로 이름 변경.
- JDK `HttpClient` 기반 자동구성 클라이언트는 `spring.threads.virtual.enabled=true`면 가상 스레드 사용(4.0 release notes).
- **Actuator**: liveness/readiness 프로브 **기본 활성화**(`management.endpoint.health.probes.enabled=false`로 끔). `management.endpoints.enabled-by-default` → `management.endpoints.access.default`(deprecated 목록). heapdump는 3.5부터 `access=none` 기본.
- **테스트**: `@SpringBootTest`가 MockMvc/TestRestTemplate/WebClient를 자동구성하지 않음 → `@AutoConfigureMockMvc`, `@AutoConfigureTestRestTemplate`, `@AutoConfigureRestTestClient` 필요. `@MockBean/@SpyBean` 제거 → `@MockitoBean/@MockitoSpyBean`. Spock 통합 제거.
- 기타: `spring.dao.exceptiontranslation.enabled`→`spring.persistence.exceptiontranslation.enabled`, DevTools LiveReload 기본 off, uber-jar 실행 스크립트 제거, `spring-boot-properties-migrator`로 속성 이름 점검.
- **Gradle**: Gradle 9 지원, **8.14 이상** 8.x도 계속 지원(현재 8.14.2는 최소선 충족).

### 4.1 (출처: Spring-Boot-4.1-Release-Notes 위키)
Spring Framework 7.0.8, **Micrometer 1.17.0 / Tracing 1.7.0**, **Flyway 12.4.0**, Hibernate Validator 9.1, OpenTelemetry 1.62.0, Kotlin 2.3.21. 신규: gRPC 지원, `InetAddressFilter`(SSRF), `spring.http.clients.cookie-handling`, `spring.datasource.connection-fetch=lazy`, `management.opentelemetry.enabled`, `spring.config.import=...[encoding=utf-8]`, `@Async` 컨텍스트 전파, info 엔드포인트 프로세스 메타데이터. 제거: layertools jar mode, `-DskipTests`의 AOT 제어. Deprecated: LiveReload, Derby.

### 4.2 로드맵
4.2.0-M2(2026-09-25). 상세 변경 **미확인**. 관례상 2026-11 GA.

### 이 시스템 함의·난이도·권고
- 난이도 **중**: Jackson 3 groupId/애너테이션 변경, starter 이름 변경(web→webmvc, flyway/restclient/webclient 분리), 테스트 애너테이션 변경, Hibernate 7 동반. 코드량이 작으면 1~2일, 테스트 포함 1주.
- 권고: 4.0.x는 건너뛰고 **4.1.x**로 직행(4.0 OSS EOL 2026-12-31). 이관 전 3.5에서 `WebClient.block()`→`RestClient`, `@MockBean`→`@MockitoBean`(3.4+에서 이미 가능), `spring.http.reactiveclient.*` 의존 제거를 선행하면 4.x diff가 줄어듦.

---

## 2. Spring Modulith

| 항목 | 값 | 출처 |
|---|---|---|
| 1.4 최신 | **1.4.13 (2026-08-26)** — Boot 3.5.16 기반, ConcurrentModification 수정 | GitHub releases |
| 2.0 GA / 최신 | **2025-11-21** / 2.0.8 (2026-08-26, Boot 4.0.8) | spring.io/blog/2025/11/21/…; GitHub releases |
| 2.1 GA / 최신 | **2026-06-11** / **2.1.1 (2026-08-26, Boot 4.1.1)** | spring.io/blog/2026/06/11/…; GitHub releases |
| 2.2 | 2.2.0-M1(2026-08-26), M2 존재(Maven Central) — Boot 4.2/Framework 7.1 M 기반 | GitHub releases; Maven metadata |

### 2.0 변경점 (출처: GitHub 2.0.0 release, 블로그, reference/events.html)
- 기준선 **Boot 4.0 / Framework 7.0**, jMolecules 2025.0, ArchUnit 1.4.1, Testcontainers 2.0, jSpecify 널 애너테이션. `@ApplicationEventListener` 제거.
- **보강 확인 — 우리 스키마와의 차이**: 현행 참조 스키마의 `event_publication`에는 `status TEXT`, `completion_attempts INT`, `last_resubmission_date TIMESTAMPTZ` 컬럼이 있다(부록 DDL). 우리 `V1__event_store.sql`은 구형(1.1) 6컬럼이라 **2.x 이관 전 컬럼 추가 마이그레이션이 필요**하다(널 허용 추가라 1.4에서도 무해 → 선행 Expand 가능). 아카이브 모드면 `event_publication_archive` 테이블도 필요.
- **Event Publication Registry 전면 개편**: `EventPublication.Status`(PUBLISHED / PROCESSING / COMPLETED / FAILED / RESUBMITTED) 도입, `completion_attempts`·`last_resubmission_date` 추적, **`FailedEventPublications` API**, `ResubmissionOptions`(batchSize / maxInFlight / minAge / filter), **staleness 모니터**(`spring.modulith.events.staleness.published|processing|resubmitted`, 기본 0=비활성). → **event_publication 테이블 스키마 변경**(status 등 컬럼 추가). Flyway 관리 시 마이그레이션 스크립트 필요. DDL은 위 보강 확인 항목 참조.
- 완료 이벤트 정리: `spring.modulith.events.completion-mode=UPDATE(기본)|DELETE|ARCHIVE(1.3+)`. UPDATE는 무한 증가 → `CompletedEventPublications.purgePublishedOlderThan(Duration)` 주기 호출 필요.
- 재발행: `spring.modulith.events.republish-outstanding-events-on-restart=true`(재시작 시 미완료 재제출). 2.x에서는 `IncompleteEventPublications.resubmitIncompletePublications(ResubmissionOptions)` 권장.
- 모듈별 Flyway 마이그레이션, 기동 시 모듈 구조 검증, Jackson 3 직렬화, 외부화 직렬 실행 `spring.modulith.events.externalization.serialize-externalization=true`.
- 2.1: **Namastack / JobRunr 아웃박스**(`spring.modulith.events.externalization.mode=outbox`), Boot slice test와 `@ApplicationModuleTest` 결합, `PublishedEvents`/`Scenario`가 모든 스레드의 이벤트를 봄, 관측성 인프라 정비.
- 외부화 경로: `@Externalized("topic::#{routingKey}")` + `spring-modulith-events-kafka`(토픽=라우팅 키, 메시지 키), AMQP, JMS, `spring-modulith-events-messaging`(Spring Messaging 채널; Spring Cloud Stream 연동 경로). 기본 JDBC 스키마 자동생성 `spring.modulith.events.jdbc.schema-initialization.enabled=true`(테이블 존재 시 skip).
- 문서: https://docs.spring.io/spring-modulith/reference/ (events.html, testing.html)

### 함의·난이도·권고
- 난이도 **중**: 코드 API 자체 변화는 작으나 **event_publication 스키마 변경**과 Boot 4 동반이 핵심. 이관 전 완료 이벤트를 purge/ARCHIVE로 비워 테이블을 작게 만들고, Flyway로 컬럼 추가 마이그레이션을 작성·검증(Testcontainers PG 16)한다.
- 지금(1.4): `completion-mode=ARCHIVE` 또는 주기적 purge 도입, `republish-outstanding-events-on-restart=true`는 24h 운영 재시작 시 중복 처리(멱등성) 확인 후 켤 것.
- 외부화(Kafka)는 단일 JVM에 불필요. 필요해지면 2.1 아웃박스(Namastack) 경로가 자체 Kafka보다 운영 부담이 적음.

---

## 3. JDK

| 항목 | 값 | 출처 |
|---|---|---|
| JDK 25 GA | **2025-09-16**, LTS | openjdk.org/projects/jdk/25 |
| Temurin 25 | 2025-09-22 첫 릴리스, 최신 25.0.4.1+1(2026-08-19), 지원 **2031-09-30** | endoflife.date/eclipse-temurin |
| Oracle JDK 25 최신 | 25.0.4.1(2026-08-18) | endoflife.date/oracle-jdk |
| JDK 27 | 2026-09-15 GA(비-LTS) | endoflife.date |

### JDK 25 기능 (출처: openjdk.org/projects/jdk/25)
- **JEP 506 Scoped Values(정식)**, **JEP 519 Compact Object Headers(정식)**, JEP 505 Structured Concurrency(**5차 프리뷰, 아직 정식 아님**), JEP 502 Stable Values(프리뷰), JEP 514/515 AOT 명령행 인체공학·메서드 프로파일링(Leyden), JEP 521 Generational Shenandoah, JEP 509/518/520 JFR 개선, JEP 511 모듈 import, JEP 512 compact source, JEP 513 유연한 생성자, JEP 510 KDF, JEP 503 32-bit x86 포트 제거.
- **JEP 491(synchronized 시 가상 스레드 핀닝 해결)은 JDK 24에서 통합**되어 JDK 25 LTS에 포함됨(JDK 25 목록에 별도 등재되지 않는 것은 24에서 이미 들어갔기 때문). JDK 21에서는 `synchronized` 블록 내 블로킹 시 캐리어 스레드 핀닝 발생 → **현재 JDK 21 + 가상 스레드 환경에서는 `synchronized` 대신 `ReentrantLock` 사용** 또는 JDK 25 이관이 근본 해결.
- Boot의 JDK 지원(보강 확인): **Boot 3.5.16 = Java 17~25 호환, Gradle 7.6.4+/8.4+**, **Boot 4.1.1 = Java 17~26 호환, Gradle 8.14+ 및 9.x**(docs.spring.io system-requirements). Gradle의 Java 25 툴체인·실행 지원은 **9.1.0부터**(Gradle 호환표).

### 21→25 마이그레이션 함정
- `sun.misc.Unsafe` 메모리 접근 메서드: JDK 24부터 사용 시 경고(JEP 498), 향후 제거 예정. Netty/Caffeine 등 라이브러리 최신화로 대응. `--sun-misc-unsafe-memory-access=warn|debug`로 위치 확인.
- JNI/JNA: JEP 472(JDK 24) — 네이티브 접근 제한 경고. **JNA 사용(절전 차단) 시 `--enable-native-access=ALL-UNNAMED` 추가 권장**. JNA 최신 버전 **미확인**(5.14 → 5.17+ 존재 추정).
- Security Manager 완전 제거(JEP 486, JDK 24). 32-bit x86 제거.
- 가상 스레드 운영 주의: ThreadLocal 남용(메모리), `synchronized` 핀닝(21), JDBC 드라이버 풀(HikariCP) 크기 = 캐리어가 아닌 DB 커넥션 한도, 무한 대기 방지(타임아웃 필수), reactor-netty 이벤트루프와 무관.

### 권고
난이도 **낮음**. **정정**: Boot 3.5에서는 Gradle 9.1+를 쓸 수 없어 JDK 25 툴체인으로 빌드할 수 없다 → **Boot 4.1 → Gradle 9.1+ → JDK 25** 순서. (런타임만 25로 올리고 빌드는 21로 하는 분리 운영은 테스트 JVM과 운영 JVM이 달라져 비권고.) 검증: 전체 테스트 + 24h 소크 실행, JFR로 `jdk.VirtualThreadPinned` 이벤트 확인.

---

## 4. 영속성

| 항목 | 값 | 출처 |
|---|---|---|
| Hibernate 6.6 최신 | 6.6.58 (2026-09-20), 6.6 EOL 2026-06-09 (endoflife 기준) | endoflife.date/hibernate-orm |
| Hibernate 7.x | 7.0(2025-05-20) → 7.1(Boot 4.0 기준선) → 7.2 → 7.3 → **7.4.11 (2026-09-27)** | endoflife.date/hibernate-orm |
| Flyway 최신 | **13.8.1 (2026-09-29)**; Boot 3.5 BOM 11.7.x, Boot 4.1 BOM 12.4.0 | Maven Central; Boot release notes |
| PostgreSQL | 16.15(2026-08-10, EOL **2028-11-09**), 17.11(EOL 2029-11-08), **18.6(2025-09-25 GA, EOL 2030-11-14)**, 14는 2026-11-12 EOL | endoflife.date/postgresql |

### Hibernate 6.6 → 7.x
- 7.0: **JPA 3.2 / Jakarta Persistence 3.2**, `hibernate-jpamodelgen`→`hibernate-processor`, 레거시 Criteria/deprecated API 제거, `@Table(catalog/schema)` 처리 변경, 기본 `jakarta.persistence.schema-generation` 동작 정비, 널 안전 애너테이션. 상세 마이그레이션 가이드: https://docs.jboss.org/hibernate/orm/7.0/migration-guide/migration-guide.html (**세부 항목 미확인**).
- 함의: 엔티티가 단순(분봉·주문·체결)하면 영향 적음. `spring.jpa.hibernate.ddl-auto=validate` + Flyway로 스키마 검증하면 7.x 차이를 조기에 잡음.

### Flyway
- Boot BOM 버전을 따르는 것이 안전(3.5: 11.x, 4.1: 12.4). 13.x는 Boot 4.2 이후로 추정(**미확인**). Community 에디션의 PostgreSQL 지원 버전 정책(오래된 PG 버전 제외)은 **미확인** — 16/17/18은 최신 커뮤니티 에디션에서 지원됨.
- Boot 4에서는 **`spring-boot-starter-flyway` 명시 필요**.

### PostgreSQL / 시계열 파티셔닝 / 백업
- 16은 2028-11까지 안전. 17/18 이점(VACUUM 메모리 개선, 18의 비동기 I/O 등)은 이 규모(단일 PC, 분봉)에는 체감 적음. **PG 메이저 업그레이드는 `pg_upgrade` 또는 dump/restore 필요**(Docker 볼륨 교체 시 주의).
- 분봉 축적: 종목×분 단위로 연 수천만 행 가능 → **네이티브 선언적 파티셔닝(RANGE by month)** + `pg_partman`(자동 파티션 생성/보존) 권장. 파티션 키는 `ts TIMESTAMPTZ`. 인덱스 `(symbol, ts)` 파티션별. 오래된 파티션은 DETACH → 압축 보관.
- **TIMESTAMPTZ 모범**: 저장은 `timestamptz`(UTC 내부 저장), 세션 `TimeZone=Asia/Seoul`은 표시용, JVM `-Duser.timezone=Asia/Seoul` 고정, JDBC는 `OffsetDateTime`/`Instant` 매핑, `hibernate.jdbc.time_zone=UTC` 명시. 장 마감 계산은 `ZonedDateTime` + `Asia/Seoul`.
- 백업: 단일 PC 규모는 **`pg_dump -Fc` 일일 + Docker 볼륨 스냅샷**으로 충분. pgBackRest는 PITR·증분이 필요할 때(운영 부담 큼). 최소한 `event_publication`·주문/체결 테이블은 별도 논리 백업.

### 권고
난이도: Hibernate 7 **중**(Boot 4 동반), PG 업그레이드 **낮음이나 불필요**. 지금은 파티셔닝·백업·TIMESTAMPTZ 정합성 점검이 우선.

---

## 5. HTTP 클라이언트

- **RestClient**(Boot 3.2+, Framework 6.1+)가 동기 앱 권장. Boot 4.1 문서: "RestClient — imperative/blocking 권장, RestTemplate — deprecated 권고, WebClient — WebFlux용". (출처: docs.spring.io/spring-boot/reference/io/rest-client.html)
- `WebClient.block()`의 문제: reactor-netty 이벤트루프 위에서 블로킹하면 `IllegalStateException`(block()/blockFirst() are blocking, not supported in thread reactor-http-nio) 위험, 스택트레이스 난독, 타임아웃 이중 관리, 가상 스레드 이점 소실. RestClient는 가상 스레드와 자연 결합(JDK HttpClient 팩토리는 `spring.threads.virtual.enabled=true` 시 가상 스레드 사용, 4.0).
- 팩토리 선택: Boot 자동 감지 순서 Apache → Jetty → Reactor Netty → **JDK HttpClient** → Simple. 키움 REST(HTTP/1.1, JSON)에는 **JDK HttpClient**(의존성 0, HTTP/2 지원)나 Apache 5(풀·재시도 세밀 제어)가 적합. reactor-netty는 WebFlux 미사용 시 제거 가능 → 의존성 감소.
- 속성: 3.5 `spring.http.client.{factory,connect-timeout,read-timeout,redirects,ssl.bundle}` / `spring.http.reactiveclient.*` → 4.0에서 **`spring.http.clients.{connect-timeout,read-timeout,redirects,ssl.bundle}`, `spring.http.clients.imperative.factory`, `spring.http.clients.reactive.connector`**(구 이름은 deprecated).
- **`@HttpExchange` 인터페이스 클라이언트**: Framework 6.1+에서 `HttpServiceProxyFactory`로 수동 구성 가능, **Boot 4.0부터 자동구성**(`@ImportHttpServices(group=..)`, `spring.http.serviceclient.<group>.base-url|connect-timeout|read-timeout`). 키움 API를 인터페이스로 선언하면 테스트 대체가 쉬움.
- **Resilience4j**: 최신 **2.4.0 (2026-03-14, resilience4j-spring-boot3)**. 보강 확인: 이 앱은 Spring 통합 starter가 아니라 **코어 모듈(`resilience4j-ratelimiter`, `-retry`)만 직접 사용**(app/build.gradle)하므로 Boot 4 호환 문제가 없다. Boot 4 전용 아티팩트(`resilience4j-spring-boot4`) 존재 여부는 **미확인**(이 앱에는 무관) — 2.3.x가 Boot 3.x 기준, Boot 4 호환은 릴리스 노트 확인 필요. 대안: Spring Framework 7 자체 `@Retryable`/`@ConcurrencyLimit`(spring-resilience, 7.0 신설) — 리밋·재시도만 필요하면 R4j 제거 가능(**API 세부 미확인**).
- WebSocket 클라이언트: `spring-websocket`의 `StandardWebSocketClient`(Tyrus/Tomcat 컨테이너 필요, 서버 스택 공유) vs **JDK `java.net.http.WebSocket`**(의존성 0, 리스너 기반, 재연결·ping 직접 구현) vs Java-WebSocket(경량, 유지보수 커뮤니티). 키움 WS 1~2 커넥션·재연결·heartbeat가 핵심이면 JDK WebSocket + 자체 재연결 루프가 가장 단순. spring-websocket 유지 시 Boot 4에서 `spring-boot-starter-websocket` starter 이름 유지 여부 **미확인**.

### 권고
지금(3.5): `WebClient.block()` → `RestClient`(JDK HttpClient 팩토리) 전환, 타임아웃(connect 3s/read 10s 등) 명시, R4j retry/ratelimiter는 RestClient 호출을 감싸는 데코레이터로 유지. 난이도 **낮음**, 롤백은 브랜치 단위.

---

## 6. 관측성

| 항목 | 값 | 출처 |
|---|---|---|
| Micrometer | Boot 3.5 → 1.15.x, Boot 4.1 → **1.17.0 / Tracing 1.7.0**, 1.18.0-M2 진행 중, Tracing 1.7.1 최신 안정 | Boot 3.5/4.1 release notes; Maven Central |
| Boot 4.0 Micrometer | 1.16.x (**패치 미확인**) | — |

- OpenTelemetry Java Agent vs Micrometer Tracing: 단일 JVM 로컬 앱은 **Micrometer Tracing + OTLP 브리지**(또는 Actuator Prometheus만)로 충분. 에이전트는 제로코드지만 JVM 옵션·버전 충돌 관리 부담. Boot 4.1: `management.opentelemetry.enabled`로 SDK 선택 비활성, `@Async` 컨텍스트 전파.
- Prometheus+Grafana 로컬: `micrometer-registry-prometheus` + `management.endpoints.web.exposure.include=health,prometheus,metrics` + Docker compose(prometheus scrape `host.docker.internal:8080/actuator/prometheus`). 루프백 바인딩 시 Prometheus 컨테이너에서 접근 가능하도록 `host.docker.internal` 사용.
- 구조화 로깅: Boot 3.4+ `logging.structured.format.console=ecs|logstash|gelf`, 3.5에서 `logging.structured.json.stacktrace.*`(길이·루트 원인 우선). 파일은 `logging.structured.format.file`.
- 로그 마스킹: 계좌번호·토큰은 Logback `%replace` 패턴 또는 커스텀 `JsonWriterCustomizer`(structured)로 처리; 애초에 토큰을 로그에 넣지 않는 것이 우선.
- Actuator 보안: 3.4+ `management.endpoint.<id>.access=none|read-only|unrestricted`, `management.endpoints.access.default`(4.0에서 `enabled-by-default` 대체). 4.0: liveness/readiness 기본 노출. heapdump 기본 none(3.5).

### 권고
난이도 **낮음**. 즉시: Prometheus 엔드포인트 + 구조화 로깅 파일 출력. 트레이싱은 필요 시.

---

## 7. 보안 (개인 로컬 앱)

- Spring Security: Boot 3.5 → 6.5.x, Boot 4.0 → **7.0.x**, 4.1 → 7.1.x(7.2.0-M2 2026-09-24 진행). 정확한 최신 패치 **미확인**(Maven 조회 rate limit).
- 최소 구성: `SecurityFilterChain` 1개, `httpBasic()` + `InMemoryUserDetailsManager` 1명(비밀번호는 환경변수·`{bcrypt}`), `csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))`로 SPA 호환(또는 API만이면 `csrf.disable()` + SameSite 쿠키). Security 7: `authorizeHttpRequests` 람다 DSL만 유지, `and()` 제거(6.x에서 이미 deprecated).
- 루프백 바인딩(`server.address=127.0.0.1`) 유지 시 원격 접근은 **Tailscale**(WireGuard, 인증 내장) 또는 SSH 터널. 이 경우 Basic 인증도 이중 방어로 유지. 공인 포트 개방 금지.
- `actuator/shutdown` 대체: 엔드포인트를 끄고(`management.endpoint.shutdown.access=none`) **`server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase=30s`**, Windows 서비스 stop 신호(WinSW/NSSM이 SIGTERM 상당 처리) 또는 `SpringApplication.exit()`를 호출하는 로컬 전용 핸들러.
- 비밀 관리: Spring Boot는 **`.env` 파일을 기본 지원하지 않음**. `spring.config.import=optional:file:.env[.properties]`는 3.x/4.x 모두 유효(properties 파서로 읽음; `export KEY=` 형식은 안 됨). 4.1은 `[encoding=utf-8]` 추가. 환경변수(Windows 사용자 환경변수 또는 WinSW `<env>`)가 서비스 운영에 더 안전. `.env`는 `.gitignore`.
- Windows 관례: WinSW XML `<env name="KIWOOM_APP_KEY" value="..."/>` 또는 `setx` 사용자 환경변수 → 서비스 계정 일치 확인.

### 권고
난이도 **낮음**. 즉시: shutdown 엔드포인트 off + graceful shutdown, 비밀은 환경변수, Basic 1인 계정.

---

## 8. 테스트·빌드 도구

| 항목 | 값 | 출처 |
|---|---|---|
| JUnit | **6.1.3 (2026-08-07)** 최신; 5.x 라인은 5.13이 마지막(**미확인**) | Maven Central |
| Testcontainers | **2.0.5 (2026-04-20)**; Modulith 2.0이 TC 2.0 기준 | Maven Central; Modulith 2.0 notes |
| ArchUnit | **1.5.1 (2026-09-25)**; Modulith 2.2 M1이 1.5.0 사용 | Maven Central; Modulith releases |
| Mockito | 5.24.0 (2026-09-23) | Maven Central |
| zonky embedded-postgres | 2.2.2 (2026-03-01) | Maven Central |
| Gradle | 8.14.5 (2026-05-07, 8.x 최신), **9.8.0 (2026-09-23)** | endoflife.date/gradle |
| springdoc | 3.1.1 (2026-09-06) — Boot 4 계열; 2.8.x는 Boot 3 | Maven Central |

- JUnit 6: Java 17 기준선, JUnit 4 Vintage 분리. **보강 확인: Boot 4.0/Framework 7에서 JUnit 6이 표준**이며 "2년 넘게 deprecated된 API만 제거되어 대부분 드롭인 교체"(rieckpil 정리). **Testcontainers 2.0은 모듈명에 `testcontainers-` 접두 — `org.testcontainers:postgresql` → `org.testcontainers:testcontainers-postgresql`**, JUnit 4 지원 제거. 이 리포는 `@MockBean` 사용 0건(grep)이라 해당 변경 영향 없음.
- Testcontainers 2.0: 아티팩트/패키지 재편(Boot 4 + Modulith 2.0 기준). `@ServiceConnection` 사용 시 Boot 4 `spring-boot-testcontainers` 모듈 분리 확인.
- Mockito 5 + JDK 25: 인라인 mock maker는 ByteBuddy 에이전트 동적 attach를 사용 → JDK 21+에서 "dynamically loaded agent" 경고, 향후 기본 금지 예정. **`-XX:+EnableDynamicAgentLoading`** 또는 Gradle `test { jvmArgs("-javaagent:${mockito-core jar}") }`로 명시 attach 권장.
- Gradle 9: Kotlin DSL 기본(Groovy DSL 계속 지원, 신규 프로젝트 권고만), 설정 캐시 강화(9.x에서 기본 활성 여부 **미확인**), Java 17+ 실행 기준. **보강 확인: Boot 4.1은 Gradle 8.14+ 및 9.x 지원** → Boot 4.1 이관은 Gradle 8.14.x로 먼저 하고, Gradle 9.1+는 다음 단계로 분리(변경 원인 격리).
- Boot 4 테스트 슬라이스: `spring-boot-starter-<tech>-test` 분리, `@SpringBootTest`의 MockMvc 자동구성 제거(1절 참조). Modulith 2.1은 `@ApplicationModuleTest`+슬라이스 결합 지원.

### 권고
즉시: ArchUnit 1.5.1, Mockito 에이전트 명시 attach. 6개월 내: Gradle 9 → JUnit 6 → Testcontainers 2 순, 각 단계 CI 그린 후 다음.

---

## 9. 빌드·배포

- 기동 시간: Boot 3.3+ **CDS(`java -XX:ArchiveClassesAtExit`)** 지원, `bootBuildImage`/`extractJar`로 AppCDS 아카이브 생성(3.3+ `spring.aot`+CDS 학습 실행). **JDK 24 JEP 483 AOT 클래스 로딩·링킹 캐시 → JDK 25 JEP 514/515**로 `-XX:AOTCacheOutput=app.aot` 한 번의 학습 실행으로 캐시 생성, `-XX:AOTCache=app.aot`로 기동. 24h 상주 앱은 기동 시간이 중요하지 않으므로 **우선순위 낮음**; 재시작 복구 시간(수십 초) 단축 목적이면 JDK 25 AOT cache가 가장 간단.
- 네이티브 이미지(GraalVM 25+, Boot 4): JNA·리플렉션·동적 프록시(Modulith, Hibernate) 때문에 비용 큼 → **하지 말 것**.
- jlink: Temurin 25 + `jlink --add-modules $(jdeps ...)`로 100MB급 런타임 축소 가능하나 단일 PC에서는 효익 낮음.
- Windows 서비스화: **WinSW**(XML 설정, stop 시 graceful, 로그 회전, 재시작 정책) 권장. NSSM은 유지보수 정체. Task Scheduler는 로그인 세션 종속·재시작 정책 빈약. 절전 차단(JNA `SetThreadExecutionState`)은 서비스 세션(Session 0)에서도 동작하나 **디스플레이 관련 플래그는 무의미**; 전원 옵션에서 절전 자체를 끄는 것이 더 확실.
- Docker 컨테이너화: Linux 컨테이너에서는 Windows 전원 API(JNA) 호출 불가 → 절전 차단은 호스트에서 처리해야 함. WSL2/Docker Desktop은 호스트 절전 시 함께 멈춤. 앱은 호스트 JVM, DB만 Docker가 현 구조상 합리적.

### 권고
즉시: WinSW로 서비스화 + graceful shutdown + 자동 재시작. 이후: JDK 25 이관 시 AOT cache 시험(선택).

---

## 10. 프로젝트 관점 권고 요약

### (a) 지금 즉시 (Boot 3.5.16 유지, 2026-10 ~ 11)
| 조치 | 위험 | 롤백 | 검증 |
|---|---|---|---|
| 3.5.16 고정 인식: OSS EOL 상태이므로 **CVE 모니터링**(Spring 보안 공지) 및 6개월 내 이관 계획 확정 | 보안 패치 부재 | — | spring.io/security-advisories 구독 |
| `WebClient.block()` → `RestClient`(JDK HttpClient), 타임아웃 명시, R4j 데코레이터 유지 | 응답 파싱 차이 | 브랜치 revert | 키움 API 계약 테스트(WireMock/MockRestServiceServer) |
| `@MockBean`→`@MockitoBean`, `spring.http.reactiveclient.*` 의존 제거 | 없음 | revert | 테스트 그린 |
| Modulith: `completion-mode=ARCHIVE` 또는 주기 purge; 재발행 on-restart는 멱등성 확인 후 | 중복 처리 | 속성 원복 | event_publication 행 수 모니터링 |
| 운영: WinSW 서비스화, `server.shutdown=graceful`, shutdown 엔드포인트 off, 비밀 환경변수화, Prometheus 노출, 구조화 로그 파일 | 서비스 계정 권한 | 수동 실행 복귀 | 재부팅 후 자동 기동 확인 |
| DB: 분봉 테이블 월 RANGE 파티셔닝 + pg_partman, `pg_dump -Fc` 일일 백업, TIMESTAMPTZ 통일 | 마이그레이션 중 다운타임 | 덤프 복원 | 복원 리허설 1회 |
| 마이너 업그레이드: ArchUnit 1.5.1, Mockito 에이전트 attach, PG 16.15 유지 | 낮음 | 버전 원복 | CI |

### (b) 6개월 내 (2026-11 ~ 2027-03) — 순서와 선행 조건
(보강 정정: 순서를 뒤집었다 — Gradle 9.1+가 JDK 25의 선행조건이고, Boot 3.5는 Gradle 9를 지원하지 않는다.)
1. **Spring Boot 4.1.x + Modulith 2.1.x + Hibernate 7 + Jackson 3 + Security 7 + springdoc 3.x + Testcontainers 2 + JUnit 6** — Gradle 8.14.x·JDK 21 유지, 한 브랜치에서 동시 진행(상호 의존).
2. **Gradle 9.1+** — 선행: 1 완료. 검증: 빌드·설정 캐시 그린. 롤백: wrapper 버전 원복.
3. **JDK 25(Temurin)** — 선행: 2 완료, 라이브러리 최신화(Netty/Caffeine/JNA), `--enable-native-access=ALL-UNNAMED`, Mockito 에이전트. 검증: 24h 소크 + JFR 핀닝 이벤트 0 확인. 롤백: 툴체인 21 재지정(코드 변경 없음).
(아래 원문 3단계 설명은 1단계 내용이다.)
- Boot 4.1 단계 상세: 선행: (a)의 RestClient/테스트 정리 완료, **event_publication 스키마 마이그레이션 Flyway 스크립트** 작성·TC 검증, `spring-boot-properties-migrator`로 속성 점검, starter 이름 변경(webmvc/flyway/restclient/websocket). 검증: ArchUnit·Modulith 검증 테스트, 모의투자 계정으로 1주 병행 운영. 롤백: 3.5 브랜치 + DB는 스키마 변경이 컬럼 추가만이면 하위 호환(구 버전이 새 컬럼 무시) — 사전에 확인.
4. 이관 완료 후 4.2(2026-11 GA 예상)는 4.1 OSS 종료(2027-07) 전에 재평가.

### (c) 하지 말 것
- **Boot 4.0.x로 이관**(2026-12-31 OSS EOL, 3개월 남음) — 4.1.x로 직행.
- **Kafka/외부화 도입** — 단일 JVM에 불필요, 운영 부담만 증가. 필요 시 Modulith 2.1 아웃박스.
- **PostgreSQL 18 즉시 업그레이드** — 16은 2028-11까지 지원, 이득 미미, `pg_upgrade` 리스크.
- **GraalVM 네이티브 이미지** — JNA·Hibernate·Modulith 리플렉션 비용 대비 이득 없음.
- **앱의 Docker 컨테이너화**(Windows 절전 차단 불가) — DB만 Docker.
- 3.5.x를 2027년까지 방치 — 보안 패치 부재.

---

## 버전 매트릭스 (현재 → 최신 안정 → 권고 시점)

| 구성요소 | 현재 | 최신 안정(2026-10-01) | 권고 목표 | 시점 |
|---|---|---|---|---|
| JDK | 21 (Temurin) | 25.0.4.1 (Temurin 25, LTS, 2031-09까지) | 25 | 6개월 내 **3단계**(Gradle 9.1+ 뒤) |
| Spring Boot | 3.5.16 (OSS EOL 2026-06-30) | 4.1.1 (2026-08-20); 4.0.8; 4.2 M2 | 4.1.x | 6개월 내 **1단계** |
| Spring Framework | 6.2.x | 7.0.9 (2026-08-20); 7.1 M2 | 7.0.x(Boot 4.1 BOM) | Boot와 동시 |
| Spring Modulith | 1.4.12 | 1.4.13 / 2.0.8 / 2.1.1 (2026-08-26) | 즉시 1.4.13 → 2.1.x | 즉시 / Boot와 동시 |
| Gradle | 8.14.2 | 8.14.5 / 9.8.0 | 8.14.5(즉시 패치) → 9.1+ | 6개월 내 2단계 |
| Hibernate ORM | 6.6.x(Boot BOM) | 6.6.58 / 7.4.11 | Boot 4.1 BOM 버전(7.1+) | Boot와 동시 |
| Flyway | Boot BOM(11.x) | 13.8.1 (Boot 4.1 BOM 12.4.0) | Boot BOM 추종 | Boot와 동시 |
| PostgreSQL | 16 (16.15) | 16.15 / 17.11 / 18.6 | 16 유지 | 2028 이전 재평가 |
| Resilience4j | 2.2.0 | 2.4.0 (2026-03-14) | 2.4.x(Boot 4 호환 확인 후) | 즉시 2.3/2.4 시험 |
| Micrometer | 1.15.x | 1.17.0 (Boot 4.1) / Tracing 1.7.1 | Boot BOM 추종 | Boot와 동시 |
| springdoc | 2.8.9 | 3.1.1 | 3.x | Boot와 동시 |
| JNA | 5.14 | 미확인 | 최신 5.x | JDK 25 이관 시 |
| Testcontainers | 1.x(Boot 3.5 BOM 관리) | 2.0.5 | 2.0.x(아티팩트명 `testcontainers-postgresql`) | Boot와 동시 |
| zonky embedded PG | 16.15 | 2.2.2(라이브러리) | 유지 | — |
| ArchUnit | 1.4.2 | 1.5.1 (2026-09-25) | 1.5.1 | 즉시 |
| JUnit | 5.11 | 6.1.3 (2026-08-07) | 6.x | Boot와 동시 |
| Mockito | (5.x) | 5.24.0 | 5.24 + 에이전트 attach | 즉시 |
| Spring Security | 6.5.x | 7.0.x / 7.1.x (패치 미확인) | 7.x(Boot 4.1 BOM) | Boot와 동시 |

---

## 미확인 항목 (추가 조사 필요)
- ~~Boot 3.5.x의 JDK 25 공식 지원~~ → 확인(3.5.16: Java 25까지, Gradle 8.x까지)
- ~~Modulith event_publication DDL~~ → 확인(status·completion_attempts·last_resubmission_date 추가)
- Resilience4j Boot 4 전용 아티팩트 및 호환 범위; Spring Framework 7 `@Retryable` 세부
- Flyway Community 에디션의 PG 버전 지원 정책, Boot 4.2의 Flyway 13 채택 여부
- ~~Boot 4.x의 JUnit 6~~ → 확인(4.0부터 표준). JUnit 5.x 최종 버전은 미확인
- Gradle 9.x 설정 캐시 기본 활성 여부(미확인). ~~Boot 4.1의 Gradle 8 지원~~ → 확인(8.14+)
- JNA 최신 버전, Spring Security 7.0/7.1 최신 패치, reactor-netty 최신
- Boot 4에서 `spring-boot-starter-websocket` 이름 유지 여부
- Spring Boot 4.2 변경 내용

## 주요 출처
- https://endoflife.date/spring-boot · /postgresql · /hibernate-orm · /gradle · /eclipse-temurin · /oracle-jdk · /spring-framework
- https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide
- https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Release-Notes · /Spring-Boot-4.1-Release-Notes · /Spring-Boot-3.5-Release-Notes · /Supported-Versions
- https://docs.spring.io/spring-boot/4.0/appendix/deprecated-application-properties/index.html (spring.http.client(s) 속성 변경)
- https://docs.spring.io/spring-boot/reference/io/rest-client.html · https://docs.spring.io/spring-boot/3.5/reference/io/rest-client.html
- https://github.com/spring-projects/spring-boot/releases (v3.5.16 등)
- https://github.com/spring-projects/spring-modulith/releases (1.4.13, 2.0.8, 2.1.1, 2.2.0-M1, 2.0.0)
- https://spring.io/blog/2025/11/21/spring-modulith-2-0-ga-1-4-5-and-1-3-11-released/
- https://spring.io/blog/2026/06/11/spring-modulith-2-1-ga-2-0-7-and-1-4-12-released/
- https://docs.spring.io/spring-modulith/reference/events.html
- https://openjdk.org/projects/jdk/25/
- https://spring.io/blog/category/releases (4.2.0-M2, Security 7.2.0-M2 2026-09)
- Maven Central maven-metadata.xml (Flyway, Resilience4j, JUnit, Testcontainers, ArchUnit, Mockito, springdoc, zonky, Micrometer, Modulith)

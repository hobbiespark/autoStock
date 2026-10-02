package com.autostock.support;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 실제 PostgreSQL({@link PostgresTestDatabase})에 Flyway로 운영 스키마를 올리고 JPA를 검증하는 테스트의 부모.
 * 컨텍스트가 뜨는 것 자체가 Flyway 마이그레이션과 {@code ddl-auto: validate}(엔티티 ↔ 스키마 일치) 검증이다.
 * 설정이 같은 하위 클래스는 Spring 컨텍스트 캐시로 한 번만 뜬다.
 * 운영 이미지 DB가 없으면(Docker도 외부 주소도 없음) 컨텍스트를 띄우기 전에 건너뛴다({@link PostgresAvailableCondition}, D-18).
 */
@ExtendWith(PostgresAvailableCondition.class)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public abstract class PostgresDataJpaTest {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
    }
}

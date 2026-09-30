package com.autostock.risk;

import com.autostock.support.PostgresDataJpaTest;
import com.autostock.support.PostgresTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 실제 PostgreSQL에서 공시 블랙리스트 삭제가 반영되는지 검증한다(Phase 0.3, 감사 BE-P0-1, aiDoc/disclosure-blacklist-tx.md).
 *
 * <p>운영의 호출 경로(만료 배치 {@code DisclosureBlacklist.releaseExpired}, 수동 {@code remove})에는 트랜잭션이 없다.
 * 그래서 테스트도 트랜잭션 밖에서 부른다({@code NOT_SUPPORTED}) — {@code @DataJpaTest} 기본(테스트마다 쓰기 트랜잭션)이면
 * 저장소 호출이 그 트랜잭션에 합류해 결함이 가려진다. 커밋된 행은 {@link #cleanUp()}이 지운다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DisclosureBlacklistRepositoryDbTest extends PostgresDataJpaTest {

    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    DisclosureBlacklistRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from disclosure_blacklist");
    }

    private void register(String symbol, String rceptNo, LocalDate expiresOn) {
        repository.save(new DisclosureBlacklistEntity(symbol, "테스트", "PAID_IN_CAPITAL_INCREASE",
                rceptNo, expiresOn.minusDays(180), expiresOn, NOW));
    }

    private int count() {
        return jdbc.queryForObject("select count(*) from disclosure_blacklist", Integer.class);
    }

    @Test
    void 트랜잭션_밖에서_불러도_종목의_공시_이력이_실제로_지워진다() {
        register("005930", "20261001000001", LocalDate.of(2027, 3, 1));
        register("005930", "20261001000002", LocalDate.of(2027, 3, 2));
        register("000660", "20261001000003", LocalDate.of(2027, 3, 1));

        long deleted = repository.deleteBySymbol("005930");

        assertEquals(2, deleted);
        assertEquals(1, count(), PostgresTestDatabase.kind());
    }

    @Test
    void 트랜잭션_밖에서_불러도_만료된_등록이_실제로_지워진다() {
        register("005930", "20261001000011", LocalDate.of(2026, 9, 30));   // 만료
        register("000660", "20261001000012", LocalDate.of(2026, 10, 1));   // 오늘까지 유효
        register("035420", "20261001000013", LocalDate.of(2027, 1, 1));

        long deleted = repository.deleteByExpiresOnBefore(LocalDate.of(2026, 10, 1));

        assertEquals(1, deleted);
        assertEquals(2, count(), PostgresTestDatabase.kind());
    }
}

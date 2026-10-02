package com.autostock.strategy;

import com.autostock.support.PostgresDataJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 전략 판단 상태(V13) — 실제 PostgreSQL에서 새 행 저장·같은 키 갱신·전략별 조회를 확인한다.
 * 컨텍스트가 뜨는 것 자체가 V13 적용과 엔티티 ↔ 스키마 일치(ddl-auto: validate) 검증이다.
 */
class StrategyStateStoreDbTest extends PostgresDataJpaTest {

    @Autowired
    StrategyStateRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 판단일을_저장하고_같은_종목은_갱신하며_전략별로_읽는다() {
        StrategyStateStore store = new StrategyStateStore(repository);
        Instant t1 = Instant.parse("2026-10-01T00:05:10Z");
        Instant t2 = Instant.parse("2026-11-03T00:05:10Z");

        store.saveLastDecisionDate("C3-TEST", "035720", LocalDate.of(2026, 10, 1), t1);
        store.saveLastDecisionDate("C3-TEST", "005930", LocalDate.of(2026, 10, 1), t1);
        store.saveLastDecisionDate("OTHER-TEST", "035720", LocalDate.of(2026, 9, 1), t1);
        repository.flush();
        store.saveLastDecisionDate("C3-TEST", "035720", LocalDate.of(2026, 11, 3), t2);
        repository.flush();

        assertEquals(Map.of("035720", LocalDate.of(2026, 11, 3), "005930", LocalDate.of(2026, 10, 1)),
                store.loadLastDecisionDates("C3-TEST"));
        assertTrue(store.loadLastDecisionDates("NONE").isEmpty());
        Map<String, Object> row = jdbc.queryForMap(
                "select last_decision_date::text as d, created_at < updated_at as touched, version"
                        + " from strategy_state where strategy_id = 'C3-TEST' and symbol = '035720'");
        assertEquals("2026-11-03", row.get("d"));
        assertEquals(Boolean.TRUE, row.get("touched"), "생성 시각은 그대로, 갱신 시각만 바뀐다");
        assertEquals(1L, row.get("version"));
    }
}

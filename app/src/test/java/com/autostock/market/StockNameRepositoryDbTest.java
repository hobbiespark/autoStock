package com.autostock.market;

import com.autostock.support.PostgresDataJpaTest;
import com.autostock.support.PostgresTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 실제 PostgreSQL에서 종목명 사전(V10 {@code stock_names}) 검증 — 한 문장 upsert(넣기·바꾸기)와 재조회 대상 조회
 * (aiDoc/stock-names.md). 컨텍스트가 뜨는 것 자체가 V10과 엔티티 매핑(ddl-auto validate) 일치 검증이다.
 */
class StockNameRepositoryDbTest extends PostgresDataJpaTest {

    private static final Instant T1 = Instant.parse("2026-10-02T07:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-09T07:00:00Z");

    @Autowired
    StockNameRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void upsert는_없으면_넣고_있으면_이름_출처_확인_시각을_바꾼다() {
        assertEquals(1, repository.upsert("005930", "삼성전자(주)", "DART", T1));
        assertEquals(1, repository.upsert("005930", "삼성전자", "KIWOOM", T2));

        Map<String, Object> row = jdbc.queryForMap("select name, source, updated_at from stock_names where symbol = '005930'");
        assertEquals("삼성전자", row.get("name"), PostgresTestDatabase.kind());
        assertEquals("KIWOOM", row.get("source"));
        assertEquals(T2, ((Timestamp) row.get("updated_at")).toInstant());
        assertEquals(1, jdbc.queryForObject("select count(*) from stock_names", Integer.class));
    }

    @Test
    void 확인_시각이_기준보다_오래된_행만_찾는다() {
        repository.upsert("005930", "삼성전자", "KIWOOM", T1);
        repository.upsert("000660", "SK하이닉스", "KIWOOM", T2);

        List<StockNameEntity> stale = repository.findByUpdatedAtBefore(T2);

        assertEquals(List.of("005930"), stale.stream().map(StockNameEntity::getSymbol).toList());
        assertEquals("삼성전자", stale.get(0).getName());
        assertEquals("KIWOOM", stale.get(0).getSource());
        assertEquals(T1, stale.get(0).getUpdatedAt());
    }
}

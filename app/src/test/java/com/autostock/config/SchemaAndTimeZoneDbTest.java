package com.autostock.config;

import com.autostock.market.MarketHolidayEntity;
import com.autostock.market.MarketHolidayRepository;
import com.autostock.support.PostgresDataJpaTest;
import com.autostock.support.PostgresTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 PostgreSQL에서 스키마·시간대 검증. 컨텍스트 기동 = Flyway 마이그레이션 + ddl-auto validate 통과다.
 */
class SchemaAndTimeZoneDbTest extends PostgresDataJpaTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MarketHolidayRepository holidays;

    @Test
    void Flyway가_마지막_마이그레이션까지_적용되고_엔티티_검증을_통과한다() {
        Integer failed = jdbc.queryForObject("select count(*) from flyway_schema_history where not success", Integer.class);
        String latest = jdbc.queryForObject(
                "select version from flyway_schema_history where version is not null order by installed_rank desc limit 1",
                String.class);

        assertEquals(0, failed);
        assertEquals("13", latest, "새 마이그레이션을 추가했으면 이 기대값도 올린다 — " + PostgresTestDatabase.kind());
    }

    @Test
    void DATE와_TIMESTAMPTZ는_시간대_변환_없이_그대로_저장된다() {
        // A5(aiDoc/time.md): 업무 날짜는 KST로 계산한 LocalDate 그대로, 시각은 UTC Instant 그대로 저장돼야 한다
        LocalDate chuseok = LocalDate.of(2026, 9, 25);
        Instant syncedAt = Instant.parse("2026-09-30T00:30:00Z");   // KST 09:30

        holidays.saveAndFlush(new MarketHolidayEntity(chuseok, "추석", "TEST", true, syncedAt));

        String raw = jdbc.queryForObject("select holiday_date::text || '|' || "
                + "to_char(synced_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS') from market_holidays where holiday_date = ?",
                String.class, chuseok);
        assertEquals("2026-09-25|2026-09-30T00:30:00", raw, PostgresTestDatabase.kind());
        assertTrue(holidays.findById(chuseok).isPresent());
        assertEquals(1, holidays.findByHolidayDateBetween(chuseok, chuseok).size());
    }
}

package com.autostock.market;

import com.autostock.support.PostgresDataJpaTest;
import com.autostock.support.PostgresTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 분봉 저장소(V12) — 실제 TimescaleDB(운영 이미지)에서 넣기·중복 건너뛰기·최신/최초 시각·일봉 연속 집계·CHECK를 확인한다.
 * 연속 집계 갱신은 트랜잭션 안에서 돌지 않아(TimescaleDB 제약) 이 테스트는 트랜잭션 없이 돌고 시험 행을 직접 지운다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MinuteBarStoreDbTest extends PostgresDataJpaTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 운영 종목과 겹치지 않는 시험 코드. */
    private static final String SYMBOL = "T00001";

    @Autowired
    JdbcTemplate jdbc;

    private MinuteBarStore store;

    @BeforeEach
    void setUp() {
        store = new MinuteBarStore(jdbc);
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from minute_bars where symbol = ?", SYMBOL);
        // 지운 구간의 집계도 비운다(다음 실행이 같은 외부 DB를 쓸 수 있다)
        new MinuteBarStore(jdbc).refreshDailyBars(day(2026, 9, 29), day(2026, 10, 3));
    }

    @Test
    void 넣은_행_수를_돌려주고_같은_종목과_시각은_다시_넣지_않는다() {
        List<MinuteBarStore.Row> rows = List.of(row(9, 0, 258000, 30, 30), row(9, 1, 258100, 20, 50));

        assertEquals(2, store.insert(SYMBOL, rows), PostgresTestDatabase.kind());
        assertEquals(0, store.insert(SYMBOL, rows));
        assertEquals(1, store.insert(SYMBOL, List.of(row(9, 1, 258100, 20, 50), row(9, 2, 258200, 10, 60))));

        assertEquals(Optional.of(at(2026, 9, 30, 9, 0)), store.earliestBarTime(SYMBOL));
        assertEquals(Optional.of(at(2026, 9, 30, 9, 2)), store.latestBarTime(SYMBOL));
        assertEquals(Optional.empty(), store.latestBarTime("T00002"));
        Map<String, Object> saved = jdbc.queryForMap(
                "select open, high, low, close, volume, acc_volume, to_char(bar_time at time zone 'Asia/Seoul', 'YYYY-MM-DD HH24:MI') as kst"
                        + " from minute_bars where symbol = ? order by bar_time limit 1", SYMBOL);
        assertEquals(258000, saved.get("close"));
        assertEquals(257900, saved.get("low"));
        assertEquals(30L, saved.get("acc_volume"));
        assertEquals("2026-09-30 09:00", saved.get("kst"));   // 분 시작 시각이 KST 그대로 남는다
    }

    @Test
    void 일봉_연속_집계는_KST_하루_단위로_첫_시가_최고_최저_마지막_종가_거래량_합을_만든다() {
        store.insert(SYMBOL, List.of(
                row(9, 0, 258000, 30, 30),
                new MinuteBarStore.Row(at(2026, 9, 30, 9, 1), 258100, 259900, 258000, 259000, 20, 50),
                new MinuteBarStore.Row(at(2026, 9, 30, 15, 30), 259000, 259500, 257000, 258500, 100, 150),
                new MinuteBarStore.Row(at(2026, 10, 1, 0, 30), 1, 1, 1, 1, 1, 1)));   // KST 자정 뒤 — 다음 날 버킷

        store.refreshDailyBars(day(2026, 9, 30), day(2026, 10, 2));

        Map<String, Object> d = jdbc.queryForMap(
                "select open, high, low, close, volume, bars from daily_bars where symbol = ? and day = ?::timestamptz",
                SYMBOL, "2026-09-30T00:00:00+09:00");
        assertEquals(258000, d.get("open"));
        assertEquals(259900, d.get("high"));
        assertEquals(257000, d.get("low"));
        assertEquals(258500, d.get("close"));
        assertEquals(new java.math.BigDecimal("150"), d.get("volume"));
        assertEquals(3L, d.get("bars"));
        Integer nextDay = jdbc.queryForObject(
                "select count(*) from daily_bars where symbol = ? and day = ?::timestamptz", Integer.class,
                SYMBOL, "2026-10-01T00:00:00+09:00");
        assertEquals(1, nextDay);
    }

    @Test
    void 거래량이_있는데_저가가_종가보다_높은_행은_DB가_거부한다() {
        MinuteBarStore.Row broken = new MinuteBarStore.Row(at(2026, 9, 30, 9, 0), 100, 110, 105, 101, 10, 10);

        assertThrows(DataAccessException.class, () -> store.insert(SYMBOL, List.of(broken)));
        assertEquals(Optional.empty(), store.latestBarTime(SYMBOL));
    }

    private static MinuteBarStore.Row row(int hour, int minute, int close, long volume, long acc) {
        return new MinuteBarStore.Row(at(2026, 9, 30, hour, minute), close, close + 100, close - 100, close, volume, acc);
    }

    private static Instant at(int y, int m, int d, int h, int min) {
        return LocalDateTime.of(y, m, d, h, min).atZone(KST).toInstant();
    }

    private static Instant day(int y, int m, int d) {
        return LocalDate.of(y, m, d).atStartOfDay(KST).toInstant();
    }
}

package com.autostock.market;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 분봉 저장소 — TimescaleDB 하이퍼테이블 {@code minute_bars}와 일봉 연속 집계 {@code daily_bars}(V12, 2026-10-02,
 * aiDoc/minute-bars-db.md).
 *
 * <p>JPA가 아니라 JdbcTemplate 한 문장으로 넣는다(aiDoc/external-data-files.md 7절): 종목·페이지 단위로
 * {@code INSERT … SELECT FROM unnest(배열) ON CONFLICT DO NOTHING}. 왕복 1회이고, 새로 들어간 행 수가 정확히 나오며,
 * 같은 페이지를 다시 넣어도 안전하다(되채우기·따라잡기가 겹쳐도 된다).
 *
 * <p>{@link #refreshDailyBars}는 트랜잭션 안에서 돌지 않는다(TimescaleDB 제약 — 실측). 이 클래스는 트랜잭션을 열지 않으므로
 * 트랜잭션 밖(자동 커밋)에서 부르면 된다.
 */
@Repository
public class MinuteBarStore {

    /** 시각은 ISO 문자열 배열로 넘겨 DB에서 timestamptz로 바꾼다 — JVM 시간대에 기대지 않는다(aiDoc/time.md). */
    static final String INSERT_SQL = """
            INSERT INTO minute_bars (bar_time, volume, acc_volume, open, high, low, close, symbol)
            SELECT u.t, u.v, u.av, u.o, u.h, u.l, u.c, ?
            FROM unnest(?::timestamptz[], ?::bigint[], ?::bigint[], ?::int[], ?::int[], ?::int[], ?::int[])
                 AS u(t, v, av, o, h, l, c)
            ON CONFLICT (symbol, bar_time) DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    public MinuteBarStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 분봉 묶음을 넣는다. 이미 있는 (종목, 시각)은 건너뛴다. @return 새로 들어간 행 수 */
    public int insert(String symbol, List<Row> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        Integer inserted = jdbc.execute((ConnectionCallback<Integer>) con -> {
            int n = rows.size();
            String[] times = new String[n];
            Long[] volumes = new Long[n];
            Long[] accVolumes = new Long[n];
            Integer[] opens = new Integer[n];
            Integer[] highs = new Integer[n];
            Integer[] lows = new Integer[n];
            Integer[] closes = new Integer[n];
            for (int i = 0; i < n; i++) {
                Row row = rows.get(i);
                times[i] = row.barTime().toString();
                volumes[i] = row.volume();
                accVolumes[i] = row.accVolume();
                opens[i] = row.open();
                highs[i] = row.high();
                lows[i] = row.low();
                closes[i] = row.close();
            }
            try (PreparedStatement ps = con.prepareStatement(INSERT_SQL)) {
                ps.setString(1, symbol);
                ps.setArray(2, con.createArrayOf("text", times));
                ps.setArray(3, con.createArrayOf("int8", volumes));
                ps.setArray(4, con.createArrayOf("int8", accVolumes));
                ps.setArray(5, con.createArrayOf("int4", opens));
                ps.setArray(6, con.createArrayOf("int4", highs));
                ps.setArray(7, con.createArrayOf("int4", lows));
                ps.setArray(8, con.createArrayOf("int4", closes));
                return ps.executeUpdate();
            }
        });
        return inserted == null ? 0 : inserted;
    }

    /** 이 종목의 가장 최근 분봉 시각. 없으면 빈 값. */
    public Optional<Instant> latestBarTime(String symbol) {
        return instant("select max(bar_time) from minute_bars where symbol = ?", symbol);
    }

    /** 이 종목의 가장 오래된 분봉 시각. 없으면 빈 값 — 되채우기가 필요한지 판단한다. */
    public Optional<Instant> earliestBarTime(String symbol) {
        return instant("select min(bar_time) from minute_bars where symbol = ?", symbol);
    }

    /**
     * [from, to) 구간의 일봉 연속 집계를 다시 계산한다. 정책(V12)은 최근 10일만 다시 보므로, 되채운 과거 구간은 이걸로 맞춘다.
     * 경계는 일봉 버킷(KST 자정)에 맞춰 넘긴다 — 버킷을 반만 덮는 구간은 TimescaleDB가 계산하지 않는다.
     */
    public void refreshDailyBars(Instant from, Instant to) {
        jdbc.execute((ConnectionCallback<Void>) con -> {
            try (PreparedStatement ps = con.prepareStatement(
                    "CALL refresh_continuous_aggregate('daily_bars', ?::timestamptz, ?::timestamptz)")) {
                ps.setString(1, from.toString());
                ps.setString(2, to.toString());
                ps.execute();
            }
            return null;
        });
    }

    private Optional<Instant> instant(String sql, String symbol) {
        Timestamp value = jdbc.queryForObject(sql, Timestamp.class, symbol);
        return Optional.ofNullable(value).map(Timestamp::toInstant);
    }

    /** 넣을 분봉 한 행 — 가격은 원 단위 정수, 시각은 분 시작. */
    public record Row(Instant barTime, int open, int high, int low, int close, long volume, long accVolume) {
    }
}

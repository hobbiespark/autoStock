-- 분봉 저장소 — TimescaleDB 하이퍼테이블과 일봉 연속 집계 (2026-10-02, aiDoc/minute-bars-db.md)
-- 결정: 분봉은 DB(TimescaleDB 하이퍼테이블, 사용자 2026-10-01). 이미지·버전은 D-15·D-16(aiDoc/db-switch-timescale.md).
-- TimescaleDB가 있는 DB에서만 돈다 — 옛 postgres:16-alpine에서는 확장이 없어 실패한다(의도된 안전장치).
-- 실측 근거: aiDoc/data-platform-postgres.md 6.3·6.4절(월 청크·종목 세그먼트, 연속 집계 WITH NO DATA, 실시간 집계).

CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE TABLE minute_bars (
    bar_time   TIMESTAMPTZ NOT NULL,   -- 분 시작 시각(키움 cntr_tm, KST) — 시각 그대로 저장(aiDoc/time.md)
    volume     BIGINT      NOT NULL,   -- 그 분의 거래량
    acc_volume BIGINT      NOT NULL,   -- 당일 누적 거래량(API 원본 보존)
    open       INTEGER     NOT NULL,   -- 원 단위 정수(KRX 호가에 소수 없음)
    high       INTEGER     NOT NULL,
    low        INTEGER     NOT NULL,
    close      INTEGER     NOT NULL,
    symbol     VARCHAR(6)  NOT NULL,
    PRIMARY KEY (symbol, bar_time),
    CHECK (volume >= 0 AND acc_volume >= 0),
    -- 체결 없는 분(거래정지·VI 등)이 0으로 와도 잃지 않게, 거래량이 있을 때만 시고저종 일관성을 본다
    CHECK (volume = 0 OR (low > 0 AND low <= LEAST(open, close) AND high >= GREATEST(open, close)))
) WITH (
    tsdb.hypertable,
    tsdb.partition_column = 'bar_time',
    tsdb.chunk_interval   = '1 month',
    tsdb.segmentby        = 'symbol',
    tsdb.orderby          = 'bar_time DESC'
);

-- 열 압축(컬럼스토어): 끝난 지 30일 지난 월 청크를 매일 18:00 KST에 압축한다(15:45 적재·16:30 백업 뒤, 장외).
-- 표를 만들 때 자동으로 생긴 정책은 "생성 시각부터 하루 간격"이라 지우고, 시각을 고정해 다시 건다. 첫 실행은 다음 날 18:00.
CALL remove_columnstore_policy('minute_bars');
CALL add_columnstore_policy('minute_bars',
     after             => INTERVAL '30 days',
     schedule_interval => INTERVAL '1 day',
     initial_start     => (date_trunc('day', now() AT TIME ZONE 'Asia/Seoul') + INTERVAL '1 day 18 hours') AT TIME ZONE 'Asia/Seoul',
     timezone          => 'Asia/Seoul');

-- 일봉 연속 집계 — 분석·LLM용이다. C3 매매 판단은 지금처럼 키움 일봉(ka10081)을 쓴다.
-- WITH NO DATA: Flyway 트랜잭션 안에서 WITH DATA는 실패한다(실측). 채우기는 적재 잡의 구간 refresh와 아래 정책이 맡는다.
-- materialized_only = false: 아직 집계되지 않은 최근 구간도 원본에서 바로 보인다(기본값이면 오늘 일봉이 늦게 보인다 — 실측).
CREATE MATERIALIZED VIEW daily_bars
WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT symbol,
       time_bucket(INTERVAL '1 day', bar_time, 'Asia/Seoul') AS day,   -- KST 자정 시작 시각
       first(open, bar_time) AS open,
       max(high)             AS high,
       min(low)              AS low,
       last(close, bar_time) AS close,
       sum(volume)           AS volume,
       count(*)              AS bars
FROM minute_bars
GROUP BY symbol, time_bucket(INTERVAL '1 day', bar_time, 'Asia/Seoul')
WITH NO DATA;

-- 최근 10일을 매시 다시 집계한다(가볍다 — 실측). 그보다 오래된 구간은 적재 잡이 넣은 구간을 직접 refresh한다.
SELECT add_continuous_aggregate_policy('daily_bars',
       start_offset      => INTERVAL '10 days',
       end_offset        => INTERVAL '1 hour',
       schedule_interval => INTERVAL '1 hour');

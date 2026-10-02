# 분봉 DB화 — TimescaleDB 하이퍼테이블·일봉 연속 집계·1년 되채우기 (S1 2단계, 2026-10-02)

- 날짜: 2026-10-02
- 근거:
  - 분봉 저장소 결정: DB(TimescaleDB 하이퍼테이블, 사용자 2026-10-01) — `external-data-files.md` 5절
  - 설계·실측: `data-platform-postgres.md` 6.3·6.4절(월 청크·종목 세그먼트, 연속 집계, 되채우기 함정)
  - DB 전환: `db-switch-timescale.md`(10/2 18:54 운영 전환 완료 — PostgreSQL 17.11 + TimescaleDB 2.30.2)
- 사용자 확인: 10/2 19:01 "네"(전환 확인 뒤 분봉 작업 진행)
- 상태: **운영 적용 완료** — 10/2 19:23 재기동(V12 적용), 19:23~19:32 CSV 이관과 1년 되채우기(8절 결과).

## 1. 무엇이 바뀌나

| 항목 | 전 | 후 |
|---|---|---|
| 저장소 | `data/minutes/{종목}.csv`(앱이 15:45에 "오늘 행만" 추가) | DB `minute_bars` 하이퍼테이블(V12). 백업·복원 리허설에 자동 포함 |
| 놓친 날 | 그날 15:45에 앱이 꺼져 있었으면 영영 빠짐(9/11~10/1 사이 빈 날 다수) | 기동·매시 점검이 DB의 최신 분봉까지 따라잡는다 |
| 1년 창 밖 | 다시 받을 수 없음 | 첫 기동 때 키움이 주는 1년치 전부를 되채운다(종목당 약 107페이지) |
| 일봉 | 없음(C3는 키움 일봉) | `daily_bars` 연속 집계(분석·LLM용). C3 매매 판단은 그대로 키움 일봉 |
| 예전 CSV | — | 첫 동기화 때 한 번 DB로 옮긴다. 파일은 지우지 않는다 |

## 2. 스키마 — V12 `V12__minute_bars_timescale.sql`

- `minute_bars`:
  - 열: `bar_time TIMESTAMPTZ`(분 시작), `volume`·`acc_volume BIGINT`, 시고저종 `INTEGER`(원), `symbol VARCHAR(6)`.
  - 기본키 `(symbol, bar_time)` — 중복 없이 몇 번이고 다시 넣을 수 있다.
  - CHECK:
    - `volume ≥ 0`, `acc_volume ≥ 0`.
    - 거래량이 있는 분은 `0 < 저가 ≤ min(시가, 종가)`, `고가 ≥ max(시가, 종가)`.
  - 하이퍼테이블: 월 청크, 열 압축 `segmentby = symbol`, `orderby = bar_time DESC`.
- 열 압축 정책: 끝난 지 30일 지난 청크를 **매일 18:00 KST**에 압축한다(장외, 15:45 적재·16:30 백업 뒤).
  - 표를 만들 때 자동으로 생긴 정책은 "생성 시각부터 하루 간격"이라 지우고 시각을 고정해 다시 건다. 첫 실행은 다음 날 18:00.
- `daily_bars` 연속 집계:
  - KST 자정 버킷(`time_bucket('1 day', bar_time, 'Asia/Seoul')`).
  - 열: 첫 시가·최고·최저·마지막 종가·거래량 합·분봉 수.
  - `WITH NO DATA`로 만든다. Flyway 트랜잭션 안에서 `WITH DATA`는 실패한다(실측).
  - `materialized_only = false`(실시간 집계 — 기본값이면 오늘 일봉이 늦게 보인다, 실측).
  - 정책: 최근 10일을 매시 다시 집계한다.
- **TimescaleDB가 없는 DB에서는 실패한다.** 옛 16-alpine으로 되돌리면 앱이 뜨지 않는다(`db-switch-timescale.md` 6절).

## 3. 적재 잡 — `market/MinuteBarArchiver` + `market/MinuteBarStore`

- **언제:**
  - 평일 15:45 KST 정기 적재.
  - 기동 직후 한 번. 가상 스레드에서 돌아 되채우기가 길어도 기동을 막지 않는다.
  - 매시 15분 점검: 장외에 되채우기·빈 구간이 남았거나, 15:35 뒤인데 그날 동기화를 못 마쳤으면 다시 한다.
  - 동기화는 겹치지 않는다(하나만 돈다).
- **동기화 한 번:** 종목마다 ka10080을 최신 페이지부터 과거로 넘기며 넣는다(`ON CONFLICT DO NOTHING`).
  - **따라잡기:** DB의 최신 분봉과 겹치는 페이지에서 멈춘다. 매일이면 1페이지(약 2.3거래일)로 끝난다.
  - **되채우기:** 가장 오래된 분봉이 330일보다 최근이면 브로커가 주는 끝까지 간다. **장외(STANDBY)에만** 하고, 장중이면 다음 장외로 미룬다.
  - **장중 상한:** DB가 비었으면 오늘 페이지만, 있으면 12페이지(약 1개월)까지 넘긴다. 상한에 걸리거나 도중에 실패하면 "빈 구간"(아래 끝 시각)으로 기억했다가, 다음 장외에 그 아래 끝까지 메운다.
  - **끝난 분만 넣는다**(분 시작 + 1분 ≤ 지금). 장중에 진행 중인 분의 값이 굳지 않게 한다.
  - **V12 CHECK와 같은 규칙으로 걸러 낸다:** 소수·음수 가격, 거래량 있는데 시고저종이 어긋난 행. 걸린 행은 "검증 제외"로 세어 로그에 남긴다. 한 행 때문에 페이지 전체가 실패하지 않게 한다.
  - **일봉 집계:** 넣은 구간을 KST 하루 단위로 넓혀 다시 계산한다(`refresh_continuous_aggregate`, 트랜잭션 밖). 되채우기를 끝내거나 빈 구간을 다 메우면 그 종목 전 구간을 다시 계산한다 — 앞선 시도가 끊겨 집계되지 않은 구간까지 맞춘다.
- **넣는 방식(`MinuteBarStore`):** JdbcTemplate 한 문장이다 — `INSERT … SELECT FROM unnest(배열) ON CONFLICT DO NOTHING`.
  - 페이지(900행)마다 왕복 1회이고, 새로 들어간 행 수가 정확히 나온다.
  - 시각은 ISO 문자열 배열로 넘겨 DB에서 바꾼다. JVM 시간대에 기대지 않는다.
- **연속 조회(키움):**
  - `KiwoomRestClient.callPage`: 요청 헤더 `cont-yn: Y`·`next-key`를 보내고, 응답 헤더 `cont-yn=Y`면 다음 키를 돌려준다(probe 실측 규약).
  - `MarketDataPort.minuteBarPage(종목, 다음 키)`가 이를 감싼다(market은 포트만 안다 — 아키텍처 규칙).
- **속도:** ka10080은 TR별 1건/초(`TrRateLimiter`)다. 1년 되채우기는 5종목 × 약 107페이지 ≈ 9~10분이다. 다른 TR은 각자 버킷이라 막히지 않는다.

## 4. 로그 (첫 기동에서 확인할 것)

1. Flyway: `Migrating schema "public" to version "12 - minute bars timescale"` → `now at version v12`
2. CSV 이관: `분봉 CSV 이관: 삼성전자(005930) N행 중 M행 추가(이미 있음 …, 검증 제외 …) — 파일은 그대로 둔다`
3. 종목별 되채우기: `분봉 되채우기: 삼성전자(005930) 95,000행 안팎 추가(107페이지 안팎, 2025-10-0x~2026-10-02)`
4. 요약: `분봉 적재 완료(기동) — 5종목 중 실패 0, 추가 …행, 검증 제외 …행, 일봉 집계 갱신 2025-…~2026-10-02`
5. 이후 평일 15:45: `분봉 적재: … 382행 추가(1페이지 …)`, 요약 1줄

- 확인 SQL(선택, `docker exec -it autostock-db psql -U autostock -d autostock`):
  - `select symbol, count(*), min(bar_time), max(bar_time) from minute_bars group by 1;`
  - `select * from daily_bars where symbol = '005930' order by day desc limit 5;`

## 5. 버린 대안

- **적재를 JPA 엔티티로:** 행마다 INSERT가 나가 900행 페이지가 900번 왕복이다. 중복 건너뛰기도 별도 조회가 필요하다.
- **CSV도 함께 쓰기:** 저장소가 둘이면 어느 쪽이 맞는지 다시 따져야 한다. DB 하나로 백업·복원 리허설에 포함한다(`external-data-files.md` 4·5절).
- **`acc_volume ≥ volume` CHECK(10/1 권장안):** 최근 2일 실측(9/23·10/1)에서는 위반 0건이었다. 하지만 1년 전 페이지는 확인하지 못했다. 원본 값을 잃지 않으려고 `≥ 0`으로 완화했다.
- **되채우기 완료를 표로 기록:** 가장 오래된 분봉 시각(330일)으로 판단하면 표가 필요 없다. 브로커 창보다 이력이 짧은 종목(신규 상장)은 기동 때마다 그 이력만큼 다시 넘기는데, 비용이 작다.
- **기동 리스너에서 바로 되채우기:** 메인 스레드가 수 분 묶여 다른 기동 따라잡기(공모주·블랙리스트·매크로)가 밀린다. 그래서 가상 스레드에서 돌린다.

## 6. 함정과 주의

- **연속 집계 갱신은 트랜잭션 밖에서만 된다.** `MinuteBarStore`는 트랜잭션을 열지 않는다. 호출하는 쪽도 `@Transactional` 안에서 부르지 않는다.
- **과거 구간을 고친 뒤에는 그 구간을 refresh해야 한다.** 정책은 최근 10일만 보기 때문에, 하지 않으면 일봉이 옛 값으로 남는다(6.4절 실측).
- **압축된 청크에도 넣을 수 있다**(실측). 다만 느리다. 압축은 끝난 지 30일 지난 월 청크만 해서, 매일 적재는 압축되지 않은 청크에 들어간다.
- **브로커 창은 날마다 하루씩 줄어든다.** 되채우기를 미루면 그만큼 잃는다. 이번에는 전환 당일(10/2) 밤에 반영한다.
- **`docker compose down -v` 금지:** 1년 지난 분봉은 다시 받을 수 없다.
- **DB 테스트(`MinuteBarStoreDbTest`)는 트랜잭션 없이 돈다**(`@Transactional(NOT_SUPPORTED)`). 연속 집계 갱신 때문이며, 시험 종목 `T00001` 행을 직접 지운다.

## 7. 변경 파일

- 신규:
  - `db/migration/V12__minute_bars_timescale.sql`
  - `market/MinuteBarStore`
  - 테스트 `MinuteBarStoreDbTest`(3건, 실제 TimescaleDB)
- 수정:
  - `market/MinuteBarArchiver` — CSV → DB, 따라잡기·되채우기·빈 구간·집계·CSV 이관
  - `market/MarketDataPort` — `minuteBars` → `minuteBarPage`(연속 조회)
  - `market/KiwoomMarketDataAdapter`
  - `kiwoom/KiwoomRestClient` — `callPage`·`Page`. 단건 `call`은 그대로다
- 테스트:
  - `MinuteBarArchiverTest` — 9건으로 새로 씀: 장외 되채우기, 겹침 멈춤, 장중 미룸·진행 중인 분, 검증 제외, 상한·빈 구간 메우기, 한 종목 실패, CSV 한 번, 꺼짐, CSV 줄 읽기
  - `KiwoomRestClientTest` +1(연속 조회 헤더)
  - `KiwoomMarketDataAdapterTest` 수정 +1(페이지 키)
  - `SchemaAndTimeZoneDbTest`(최신 마이그레이션 12)
- 설정: 그대로(`autostock.minute-archive.enabled`·`symbols`·`dir`). `dir`은 이제 CSV 이관 원천으로만 쓴다.

## 8. 검증 상태

- **컨테이너(HA PG17.11 + TimescaleDB 2.30.2, 운영과 같은 이미지):**
  - V1~V12가 빈 DB에 한 번에 적용됐다. 열 압축 작업 첫 실행은 다음 날 09:00 UTC(18:00 KST), 고정 일정이다.
  - 전환 리허설 DB(복원된 운영 형태, V10)에는 V11·V12가 이어서 적용됐다.
  - 전체 테스트 app 668건, common 63건 통과(건너뜀 16 — 기존 실데이터·스모크). 분봉 관련 신규·수정 13건 포함.
  - `MinuteBarStoreDbTest`:
    - 중복 건너뛰기, KST 시각 보존, 일봉 버킷 경계(KST 자정), 시고저종·거래량 합.
    - CHECK 위반을 DB가 거부하는 것.
- **운영 확인(10/2 19:23 재기동, PC 로그):**
  - Flyway: V11 → V12 적용(0.27초).
    - `extension "timescaledb" already exists, skipping` WARN 1줄은 `CREATE EXTENSION IF NOT EXISTS`의 알림을 Flyway가 WARN으로 찍은 것이라 정상이다.
    - 적용된 마이그레이션은 고치지 않는다(체크섬이 바뀌어 다음 기동이 실패한다).
  - CSV 이관: 5종목 4,584행 전부 추가(이미 있음 0, 검증 제외 0). 파일은 그대로 있다.
  - 1년 되채우기(키움 연속 조회 — 자바 클라이언트로 처음 실측, 헤더 규약 그대로 통함):

    | 종목 | 추가 행 | 페이지 |
    |---|---|---|
    | 삼성전자(005930) | 94,429 | 107 |
    | SK하이닉스(000660) | 94,783 | 107 |
    | NAVER(035420) | 93,777 | 106 |
    | 카카오(035720) | 94,690 | 107 |
    | KODEX 200(069500) | 91,729 | 103 |

    - 다섯 종목 모두 범위는 2025-10-01~2026-10-02다.
    - 합계 469,408행 추가(CSV 포함 DB 약 47.4만 행), 검증 제외 0, 실패 0.
    - 19:23:28~19:32 약 9분, 종목당 약 1분 50초(TR별 1건/초).
  - 요약: `분봉 적재 완료(기동) — 5종목 중 실패 0, 추가 469408행, 검증 제외 0행, 일봉 집계 갱신 2025-10-01~2026-10-02`.
  - 1년 전 페이지까지 V12 CHECK 위반이 0건이었다. 완화한 `acc_volume ≥ 0` 대신 `≥ volume`이었어도 걸린 행은 없었을지 모른다(행별 대조는 하지 않았다).
- **남은 확인:**
  1. 10/3 18:00 열 압축 작업 첫 실행(`select * from timescaledb_information.job_stats;`).
  2. 10/6 첫 거래일 15:45 정기 적재(1페이지·약 382행/종목).

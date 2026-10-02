# PostgreSQL 최대 활용 데이터 기반 — 시계열·AI 지식·메모리·학습 (2026-10-01)

- 날짜: 2026-10-01
- 근거:
  - 사용자 방향(10/1 밤): "TimescaleDB는 나중에 옮기기보다 지금부터 구축", "현재 데이터량만이 아니라 AI·LLM·지식 구축까지 고려", "메모리 DB 등 현재 DB가 지원하는 것을 최대한 활용, 기술·노하우 습득 이점 고려"
  - 컨테이너 실측(6절) — Docker Hub의 실제 이미지를 받아 그 파일시스템으로 기동했다
  - 공식 문서·변경 기록(12절)
- 상태: 설계. **운영 변경 없음.** 사용자 결정 5건 대기(9절, D-15~D-19)
- 관련 문서: `external-data-files.md`(분봉 DB 이전 — 이 문서로 저장소 확정), `upgrade-2026-10/12-decisions.md`(D-10 LLM 범위)

## 1. 결론

- **PostgreSQL 하나를 데이터 플랫폼으로 키운다.** 한 DB 안에서 다음을 모두 쓴다.
  - 시계열: TimescaleDB
  - 벡터: pgvector
  - 한글 부분 검색: pg_trgm
  - 반정형 기록: JSONB
  - 작업 대기열·신호: `SKIP LOCKED` 대기열, LISTEN/NOTIFY
  - 휘발성 고속 저장과 메모리 상주: UNLOGGED 테이블, pg_prewarm
- **Redis·별도 벡터 DB·검색엔진·시계열 DB는 들이지 않는다.**
  - 기존 결정과 같은 방향이다: ADR-1(모놀리스), ADR-2(Kafka 보류), ADR-5(Redis 대신 Caffeine).
- **AI·LLM·지식 구축에는 "한 DB"가 결정적으로 유리하다(4절).**
  - 시세·공시·판단 근거·LLM 출력·임베딩이 한곳에 있어야, "그 시점에 알 수 있었던 것만" 한 SQL로 정확히 고를 수 있다.
  - LLM 평가에서 가장 위험한 미래 정보 누설을 구조로 막는다.
- **학습 가치도 크다(7절).**
  - 열 압축, 증분 집계, 벡터 근사 검색, 질의 튜닝을 익힌다.
  - 대용량 처리 업무와 기술 글감으로 바로 이어진다.
- **매매 경로는 확장 기능에 기대지 않는다(5절).**
  - 주문·리스크·포지션·이벤트는 일반 테이블로 둔다.
  - 용도 없는 기능의 실험은 랩 컨테이너에서 한다.
- **이미지는 `timescale/timescaledb-ha`(PostgreSQL 17.11 + TimescaleDB 2.30.2)를 다이제스트로 고정해 쓰기를 권한다.**
  - 툴킷, pgvector 0.8.6, vectorscale, pg_cron, hypopg 등 확장 96개가 들어 있다.
  - 비교: alpine 이미지는 63개다.
- **전환은 10/3(토)~10/5(월, 휴장) 연휴에 덤프·복원으로 한다.**
  - 10/2 거래일은 현 DB·현 코드 그대로 기동한다.
  - 실측으로 확인한 것: 새 이미지에서 V1~V9 마이그레이션과 DB 테스트 13건이 통과하고, 현 운영 형태의 덤프가 복원되며, 정렬 순서가 같다(6.2).

## 2. 판단 기준

| 순위 | 기준 | 뜻 |
|---|---|---|
| 1 | 매매 안전 | 확장 기능 장애나 버전 문제가 주문·리스크를 막지 않는다. 장중에는 무거운 작업을 하지 않는다 |
| 2 | AI·지식 가치 | LLM 해설·섀도 기록(Phase 3), 뉴스 감성(Phase 7), 백테스트가 같은 데이터를 시점에 맞게 쓴다 |
| 3 | 학습·노하우 | 실제 용도가 있는 기능부터 익힌다. 용도 없는 기능은 랩에서 익힌다 |
| 4 | 운영 부담 | 1인 운영이다. 구성 요소를 늘리지 않고, 백업 하나로 전부 복구한다 |

- 학습은 3순위다.
  - 같은 기능이라도 실제 용도에 붙여 익혀야 노하우가 남는다.
  - 용도 없는 확장은 운영 DB의 업그레이드·복원 부담만 늘린다.

## 3. 기능 지도 — 무엇을 어디에 쓰나

| 기능 | 이 프로젝트의 용도 | AI·지식 가치 | 학습 가치 | 운영 비용 | 단계 |
|---|---|---|---|---|---|
| 하이퍼테이블·컬럼스토어 | 분봉(1년 되채우기·매일 적재), 이후 일봉 이력·매크로 시계열 | 특성 원천, 사후 수익률 계산 | 상 — 청크·세그먼트 설계, 열 압축 | 중 — 이미지·백업 절차가 바뀐다 | S1 |
| 연속 집계 | 분봉 → 일봉·주봉, 일별 특성 | LLM 입력용 요약 수치를 미리 계산 | 상 — 증분 집계·무효화·실시간 집계 | 낮음 | S1~ |
| 툴킷 함수 | 캔들 롤업(`candlestick`), 통계 집계(`stats_agg`) | 수익률 분포 요약 | 중 | 낮음(이미지에 포함) | 분석용 |
| pgvector(HNSW, halfvec) | 공시·뉴스·문서 의미 검색, 과거 유사 국면 검색 | RAG·장기 기억의 핵심 | 상 — 근사 검색과 필터 함정 | 낮음 | S3 |
| pg_trgm | 공시 제목·종목명 한글 부분 검색 | 하이브리드 검색의 키워드 축 | 중 | 낮음 | S2~S3 |
| JSONB + GIN | LLM 입력 스냅샷·응답, API 원문, 판단 지표 | 다시 재현할 수 있는 기록 | 중 | 낮음 | S2~ |
| UNLOGGED 테이블 | 다시 만들 수 있는 큰 중간 데이터(시세 스냅샷, LLM 응답 캐시) | LLM 재호출 비용 절감 | 중 — WAL과 크래시 의미 | 낮음 | 필요 시 |
| pg_prewarm·shared_buffers | 재기동 뒤 자주 읽는 데이터를 메모리에 다시 올림 | — | 중 — 버퍼 캐시 | 낮음(기동 설정) | S1 |
| `SKIP LOCKED` 대기열·LISTEN/NOTIFY | 알림 비동기화(Phase 1), LLM 작업 대기열 | 임베딩·요약 작업 재시도 | 중 — Redis·Kafka 대안 | 낮음 | S2~ |
| pg_stat_statements·auto_explain | 느린 질의 찾기 | — | 상 — 실무에 바로 쓴다 | 낮음(기동 설정) | S1 |
| hypopg | 가상 인덱스로 효과를 미리 본다 | — | 상 | 없음 | 랩 |
| 일반 테이블·제약 | 주문·리스크·포지션·이벤트, F-3 판단 주기 | 판단 근거가 곧 학습 데이터 | — | — | 유지 |
| pg_cron | 쓰지 않음 — 스프링 스케줄·TimescaleDB 잡과 겹친다 | — | 하 | — | 랩 |
| vectorscale·VectorChord | 쓰지 않음 — 수십만 건 이하는 pgvector로 충분 | — | 중 | — | 랩 |
| 논리 복제·wal2json | 쓰지 않음 — 복제본이 필요 없다 | — | 중 — Kafka CDC와 연결 | — | 랩 |
| pgai(DB 안에서 LLM 호출) | **제외** — 2026-02부터 유지보수 중단. API 키를 DB에 둬야 한다 | — | — | — | 제외 |
| PostGIS·pgrouting·h3 | 해당 없음 | — | — | — | 제외 |

## 4. AI·LLM·지식 구축 관점

### 4.1 왜 "한 DB"가 결정적인가

- **시점 정합성.**
  - LLM은 학습 컷오프 이전 구간이 오염돼 있어 전진(모의) 검증만 유효하다(D-10 근거).
  - 전진 검증의 기록은 세 가지다: 그때 무엇을 알았나, 무엇을 말했나, 그 뒤 시장이 어땠나.
  - 셋이 같은 DB에 있어야 한 SQL로 정확히 맞출 수 있다.
- **규칙: 외부에서 들어온 사실에는 시각을 두 개 둔다.**
  - `occurred_at`: 사건이 일어난 시각.
  - `available_at`: 이 시스템이 알게 된 시각.
  - 판단·평가 질의는 항상 `available_at <= 기준 시각`으로 거른다.
  - 매크로 지표는 나중에 수정되므로 덮어쓰지 않는다. 받은 시각별로 쌓는다.
- **백업이 하나다.**
  - 시세·지식·임베딩·LLM 기록이 `pg_dump` 한 번, 복원 리허설 한 번으로 묶인다.
  - 10년 분봉을 넣어도 덤프 39MB, 복원 2초다(6.4).
- **조인 비용이 없다.** 예: 공시가 알려진 뒤의 수익률(이벤트 스터디). S2 이후의 예시다.

```sql
-- 공시가 알려진 시각 이후 5거래일 수익률 — 공시(지식)와 일봉(시계열)을 한 SQL로
SELECT d.rcept_no, d.symbol,
       a.close::float8 / b.close - 1 AS ret_5d          -- 가격이 정수라 실수로 바꿔 나눈다(6.4 함정)
FROM disclosures d
CROSS JOIN LATERAL (SELECT close FROM daily_bars        -- 기준: 알려지기 전 마지막 종가
                    WHERE symbol = d.symbol AND day + interval '15:30' <= d.available_at
                    ORDER BY day DESC LIMIT 1) b
CROSS JOIN LATERAL (SELECT close FROM daily_bars        -- 비교: 알려진 뒤 5번째 거래일 종가
                    WHERE symbol = d.symbol AND day + interval '15:30' > d.available_at
                    ORDER BY day OFFSET 4 LIMIT 1) a;
```

### 4.2 "메모리 DB" — 두 가지 뜻으로 답한다

#### (A) 인메모리 저장소 역할(Redis류) → PostgreSQL 기능으로 대응. Redis는 들이지 않는다

| 필요 | 쓰는 것 | 근거 |
|---|---|---|
| 가장 빠른 조회(현재가 1초, 일봉 1시간, 토큰) | JVM Caffeine(현행 유지) | 단일 JVM이라 네트워크를 거치지 않는 쪽이 가장 빠르다(ADR-5) |
| 재기동해도 남아야 하는 상태 | 일반 테이블 | 메모리 상태가 재기동에 날아간 결함이 반복됐다. 킬스위치·일 손실(P0, V9로 해결), C3 21일 주기(F-3, Phase 1) |
| 다시 만들 수 있는 큰 중간 데이터 | UNLOGGED 테이블 | WAL을 쓰지 않아 쓰기가 빠르다. 정상 재기동 때는 남고, 크래시 뒤에는 비워진다(실측 6.5) |
| 자주 읽는 데이터의 메모리 상주 | shared_buffers 512MB + pg_prewarm | DB가 다시 켜진 뒤(PC 재부팅 등) 버퍼 캐시를 복원한다. 재부팅 직후 첫 조회가 느린 것을 줄인다 |
| 작업 대기열·이벤트 신호 | `FOR UPDATE SKIP LOCKED` 대기열, LISTEN/NOTIFY | 업무 데이터와 같은 트랜잭션으로 커밋되고 백업에 포함된다. Redis Streams·Kafka 대신 |
| 미리 계산한 결과 | 연속 집계(일봉·특성) | 1종목 10년 일봉 조회가 495ms에서 9ms로 줄었다(실측 6.3) |

#### (B) AI의 기억(LLM 장기 기억) → 테이블 + pgvector로 만든다

| 기억 | 내용 | 저장 |
|---|---|---|
| 에피소드 기억 | 판단(`signal_decisions`)과 LLM 실행 기록, 그리고 사후 결과(5·20거래일 수익률) | 일반 테이블 `llm_runs`(목적·모델·프롬프트 버전·입력 스냅샷 JSONB·출력·비용) + 연속 집계 |
| 의미 기억 | 공시·뉴스·리포트·`aiDoc` 문서를 조각내 임베딩한 것 | `knowledge_docs`·`knowledge_chunks`(halfvec, `available_at`) |
| 교훈 | 결과에서 뽑은 규칙. **사람이 확인한 것만** 저장하고 출처를 단다 | `lessons` — LLM이 만든 교훈이 다시 근거가 되는 자기강화를 막는다 |
| 국면 기억 | 일별 시장 특성 벡터(수익률·변동성·매크로 z점수) → "과거 유사 국면 k개와 그 뒤 20일" | `regime_vectors`(64차원 정도) — LLM 없이도 쓰는 수치 검색 |

- **검색 방식:** 필터(종목·`available_at`) + 벡터 + 한글 부분 일치(pg_trgm)를 섞는 하이브리드 검색이다.
- **임베딩 모델·차원·전송 범위는 D-10(LLM 범위, 11/20)에서 함께 정한다.**
  - 로컬 모델(sidecar-nlp)을 쓰면 데이터가 밖으로 나가지 않는다.
  - 행마다 모델명·차원을 기록한다. 모델을 바꿔도 다시 계산할 수 있게 하기 위해서다.
- **Spring AI는 Phase 5 이후에 다시 본다.**
  - Spring AI 2.0(2026-06-12 GA)은 Spring Boot 4.0/4.1에 맞춰 설계됐다.
  - Boot 4.1 이관(Phase 5) 전에는 `JdbcTemplate`로 직접 쓴다.

## 5. 안전장치

1. **매매 경로는 일반 테이블만 쓴다.**
   - 대상: `orders`, `risk_state`, `risk_daily_pnl`, `event_store`, `event_publication`, `signal_decisions`.
   - 이 테이블들은 확장 기능 객체를 참조하지 않는다. 분봉·집계·지식 테이블이 망가져도 주문은 돈다.
   - C3는 지금처럼 키움 REST 일봉으로 판단한다. `daily_bars`는 분석·LLM용이다.
2. **장중에는 무거운 작업을 하지 않는다.**
   - 장외에만: 되채우기, 전 구간 refresh, 인덱스 생성, 대량 임베딩, 랩 작업.
   - TimescaleDB 압축 잡(하루 1회)의 실행 시각을 장외로 맞춘다.
   - 연속 집계 정책(1시간마다, 최근 10일)은 가볍다.
3. **버전을 고정하고 기록한다.**
   - 이미지는 다이제스트로 고정한다.
   - 백업할 때마다 확장 버전을 함께 기록한다.
   - 복원은 덤프 때와 같은 TimescaleDB 버전으로 한다. 공식 문서도 PostgreSQL·TimescaleDB 버전을 기록해 두라고 한다.
4. **테스트는 운영과 같은 이미지로 돈다(Testcontainers).** 확장 기능을 쓰는 SQL은 반드시 DB 테스트로 고정한다.
5. **비밀값은 DB에 두지 않는다.** DB 안에서 외부 API를 부르지 않는다(pgai를 뺀 이유 중 하나).
6. **학습 실험은 랩 컨테이너에서 한다.**
   - 같은 이미지에 최신 백업을 복원해 만든다. 그래서 월 1회 복원 리허설을 겸한다.
   - 운영 DB에는 "써 보기"용 확장을 만들지 않는다.
7. **시점 정합성 규칙(4.1)을 모든 외부 데이터 테이블에 적용한다.**
8. **잡 상태를 감시한다.** `timescaledb_information.job_stats`의 실패를 일일 리포트에 싣는다(S2).

## 6. 실측 (2026-10-01, 컨테이너)

- **방법:**
  - 이 환경에는 Docker가 없다. 그래서 Docker Hub에서 이미지를 받아 루트 파일시스템을 풀었다.
  - 이미지와 같은 진입점(`docker-entrypoint.sh`)·환경변수·사용자(uid)로 chroot 기동했다. DB 동작은 컨테이너와 같다.
  - Docker Desktop(Windows)에 고유한 부분(볼륨 소유권 복사, 포트)은 전환 때 PC에서 확인한다.
- **환경:** 2 vCPU, 7.8GB, 합성 데이터. 절대 수치보다 비교에 의미가 있다.

### 6.1 이미지 비교

| 항목 | `timescaledb-ha:pg17.11-ts2.30.2` (권고) | `timescaledb:2.30.2-pg16` (alpine) | 현 운영 `postgres:16-alpine` |
|---|---|---|---|
| 크기(amd64 압축) | 870MB | 575MB | — |
| 기반 | Ubuntu 22.04(glibc) | Alpine(musl) | Alpine(musl) |
| 새 DB 로캘 | C.UTF-8(코드포인트 순) | en_US.utf8(musl이라 코드포인트 순) | en_US.utf8 |
| 데이터 경로·사용자 | `/home/postgres/pgdata/data`, uid 1000 | `/var/lib/postgresql/data`, uid 70 | alpine과 같음 |
| 확장 수 | 96개 | 63개 | contrib만 |
| TimescaleDB·툴킷 | 2.30.2 · 1.26.0 | 2.30.2 · 없음 | 없음 |
| pgvector | 0.8.6 | 0.8.1 | 없음 |
| vectorscale·VectorChord | 0.9.1 · 0.5.3 | 없음 | 없음 |
| 그 밖의 확장 | pg_cron, hypopg, pg_stat_monitor, pg_qualstats, pgaudit, rum, pgtap, pg_repack, pg_uuidv7, hll, roaringbitmap, plpython3u, PostGIS 등 | plpython3u | — |
| 공식 문서 | "가장 완전한 TimescaleDB 경험"으로 안내 | — | — |

- pgvector 0.8.1은 0.8.3(2026-06-17)에서 고친 "HNSW vacuum 중 인덱스 손상 가능" 결함을 안고 있다.
- 다이제스트(태그 `pg17.11-ts2.30.2`의 이미지 인덱스): `sha256:2fcc39a5d4c8a65f58691ef92c7819df5773db72397b2dd8493f114659b519c2`. 레지스트리의 `Docker-Content-Digest` 헤더와 대조해 일치를 확인했다.

### 6.2 전환 안전성

| 확인 | 결과 |
|---|---|
| V1~V9 마이그레이션 + JPA 스키마 검증 + DB 테스트 13건 | HA 이미지의 PostgreSQL 16.15·17.11·18.6 모두 통과 |
| 18의 경고 | Flyway 11.7.2가 "PostgreSQL 18.6은 이 Flyway보다 새롭고 시험되지 않았다, 지원 최신은 17"이라고 경고 |
| 현 운영 형태의 덤프를 HA에 복원 | 현 운영 형태: 16.15 alpine, en_US.utf8. 16·17 모두 종료 코드 0, 행 수 일치. 업무 데이터 22,504행(+ Flyway 이력 9행)을 0.17초에 복원 |
| 정렬 순서 | 한글·영문 대소문자가 섞인 500행의 `ORDER BY` 결과가 두 환경에서 같다. 둘 다 코드포인트 순이다 |
| 기존 볼륨 재사용 | 불가. 데이터 경로·uid·C 라이브러리가 다르다. 덤프·복원으로 옮긴다 |
| 확장 생성 | HA 초기화가 `autostock` DB에 timescaledb·툴킷을 만든다. 복원은 그 위에 그대로 된다 |

### 6.3 분봉 — 일반 테이블과 하이퍼테이블 비교 (10년 498만 행)

- 데이터: 2016-10-03~2026-09-30 평일 2,608일 × 382분 × 5종목 = 4,981,280행(합성).
- 적재 순서는 운영과 같게 했다(하루 단위, 종목별 시간순).
- 하이퍼테이블 설정: 월 청크, `segmentby = symbol`, `orderby = bar_time DESC`.

**크기**

| 형태 | 크기 |
|---|---|
| 일반 테이블(`external-data-files.md` 7절 권장안) | 514MB(표 364 + 인덱스 150) — 7절 수치와 같다 |
| 하이퍼테이블, 압축 전 | 597MB. 인덱스 229MB(기본키 + 자동 생성된 시각 인덱스) |
| 하이퍼테이블, 컬럼스토어 정책 실행 후 | **81MB**. 압축 청크는 588MB → 69MB(8.5배). 정책 1회 5.0초 |

**조회** (5회 실행 중앙값)

| 질의 | 일반 테이블 | 하이퍼테이블 |
|---|---|---|
| 1종목·하루(최근, 비압축 구간) | 3.6ms | 4.4ms |
| 1종목·하루(과거, 압축 구간) | 4.0ms | 4.6ms |
| 1종목·1년 집계 | 19.0ms | 10.4ms |
| 1종목·1년 일봉(GROUP BY) | 52.2ms | 33.8ms |
| 전 종목·10년 평균 | 345ms | 318ms |
| 특정 분의 전 종목(횡단면) | 115ms(시각 인덱스 없음) | 4.3ms |
| 1종목·10년 일봉 | 495ms(원본에서 계산) | **9.1ms(연속 집계)** |

**적재**

| 작업 | 일반 테이블 | 하이퍼테이블 |
|---|---|---|
| 오늘 5종목 재적재(이미 있음 → 0행) | 5.5ms | 4.6ms |
| 압축 구간 하루 재적재(0행) | — | 8.8ms |
| 압축 구간 빈 날 채우기(1,910행) | — | 16.2ms |
| 1종목 1년 겹쳐 재적재(99,702행 → 0행) | 235ms | 837ms |

- `ON CONFLICT DO NOTHING` 멱등 적재와 압축 구간 되채우기가 그대로 된다.
- 1년치를 겹쳐 다시 넣으면 3.6배 느리다. 일회성 작업이라 문제없다.

### 6.4 연속 집계·백업·업그레이드

- **Flyway 호환:**
  - 확장 생성, 하이퍼테이블 생성, 연속 집계 `WITH NO DATA`, 정책 추가를 17에서 한 트랜잭션으로 실행했다. 성공했다. 16에서도 같은 구문이 트랜잭션 안에서 성공했다.
  - `WITH DATA`는 "cannot run inside a transaction block" 오류가 난다. 마이그레이션에는 항상 `WITH NO DATA`를 쓴다.
- **기본값 함정 — 실시간 집계가 꺼져 있다(`materialized_only = true`).**
  - 그래서 오늘 일봉이 정책 실행 전에는 보이지 않는다.
  - `false`로 켜면 바로 보인다(실측).
- **되채우기 함정 — 과거를 고쳐도 일봉이 옛 값으로 남는다.**
  - 과거 구간을 고친 뒤 정책(start_offset 10일)만 돌리면 그 구간 일봉이 옛 값 그대로다. 실측: 382봉 → 그대로.
  - 구간 refresh를 부르면 고쳐진다. 실측: 202봉.
  - 그래서 되채우기 잡은 끝에 해당 구간 `refresh_continuous_aggregate`를 부른다.
  - 이 프로시저는 트랜잭션 안에서 돌지 않는다. 자동 커밋 연결로 부른다.
- **툴킷:**
  - 주봉 candlestick 롤업 10ms.
  - 일간 수익률 `stats_agg`(평균·표준편차·왜도·첨도) 7ms.
  - 함정: 가격이 INTEGER라 `close / lag(close)`가 정수 나눗셈이 된다. `::float8`로 바꾼다.
- **덤프·복원:**
  - 10년 압축 분봉 + 집계 DB의 `pg_dump -Fc`: 39MB, 4.1초. 일반 테이블일 때는 55MB, 10초였다.
  - 순서: `timescaledb_pre_restore()` → `pg_restore --exit-on-error` → `timescaledb_post_restore()`.
  - 결과: 종료 코드 0, 2.0초. 행 수, 압축 청크 120개, 정책 2개가 보존됐다.
  - pre/post 없이 복원하면 "could not find hypertable with id 1"로 실패한다.
  - 덤프 중 나오는 "circular foreign-key constraints … continuous_agg" 경고는 정상이다. 공식 문서도 덤프 중 메시지는 대개 무시해도 된다고 한다.
- **확장 업그레이드:**
  - 2.29.2 → 2.30.2 `ALTER EXTENSION timescaledb UPDATE`가 성공했다.
  - 단, 새 세션(`psql -X`)의 첫 명령이어야 한다.
  - Flyway에는 넣지 않는다. 연결 풀은 "세션의 첫 명령"을 보장하지 못한다.

### 6.5 벡터·한글 검색·메모리·설정

- **pgvector** (30,000조각 × 1024차원, BGE-M3 크기 가정):
  - 크기: 표 160MB, HNSW 234MB(생성 40초), halfvec HNSW 78MB(38초).
  - 필터 없는 검색: HNSW 6.6ms.
  - 종목 필터(2%) + `available_at`: 플래너가 정확 검색을 골라 3.2ms, 재현율 10/10. **필터가 좁은 질의는 이 규모에서 벡터 인덱스 없이도 빠르다.**
  - HNSW를 강제하고 반복 스캔이 없으면 **0건**이 나온다. 필터가 인덱스 탐색 뒤에 걸리기 때문이다.
  - `hnsw.iterative_scan = relaxed_order`를 켜면 10건, 41ms. → 필터 검색에는 반복 스캔을 켠다.
- **pg_trgm** (한글 제목 20만 건, C.UTF-8):
  - `LIKE '%유상증자%'`: GIN 인덱스 11.7ms.
  - 두 글자(`'%감자%'`)는 트라이그램이 나오지 않아 순차 스캔이 된다(19ms).
  - 기본 전문 검색(`simple`)은 '유상증자결정'을 한 단어로 본다. 그래서 '유상증자'로 찾지 못한다.
  - → 한글은 pg_trgm과 의미 검색을 함께 쓴다.
- **UNLOGGED:** 정상 재기동 뒤에는 1,000행이 남았다. 크래시(즉시 종료) 뒤에는 0행이었다.
- **기동 설정:**
  - `shared_preload_libraries=timescaledb,pg_stat_statements,pg_prewarm`으로 기동을 확인했다. autoprewarm 워커가 돈다.
  - 이미지 기본 튜닝은 감지한 메모리의 25%를 shared_buffers로 잡는다. 7.8GB 환경에서 2GB가 됐다.
  - `TS_TUNE_MEMORY=2GB`면 512MB가 되지만 max_connections가 25로 줄어든다. → compose에 값을 명시한다.
  - TimescaleDB 텔레메트리는 기본 'basic'이다. → 끈다.
  - `POSTGRES_INITDB_ARGS=--data-checksums`로 데이터 체크섬이 켜지는 것을 확인했다.

## 7. 학습 로드맵 — 익히는 것과 글감

| 단계 | 익히는 것 | 글감 후보(실측 근거 있음) |
|---|---|---|
| S1 전환·분봉 | 청크·세그먼트 설계, 열 압축, 증분 집계와 무효화, 확장이 있는 DB의 덤프·복원, 이미지 다이제스트 고정 | "Flyway로 TimescaleDB 연속 집계 만들기 — WITH NO DATA", "musl에서 glibc로: 정렬 순서와 C.UTF-8", "되채우기 뒤 연속 집계가 옛 값인 이유" |
| S2 상태·관측 | pg_stat_statements·auto_explain, `SKIP LOCKED` 대기열, 시점 정합성 모델링 | "Redis 없이 PostgreSQL로 작업 대기열", "매크로 지표는 덮어쓰지 마라 — available_at" |
| S3 LLM 기억 | 임베딩, HNSW·halfvec·반복 스캔, 하이브리드 검색, LLM 실행 기록과 전진 검증 | "pgvector 필터 검색이 0건을 돌려주는 이유", "한글 검색: pg_trgm + 벡터" |
| S4 이후 | 국면 유사도 검색, 교훈 저장소 | — |
| 랩 | 툴킷 함수, vectorscale·VectorChord, hypopg, pg_cron, 논리 복제 → Kafka | Kafka 실무와 잇는 CDC |

## 8. 단계 계획

| 단계 | 시기 | 작업 | 완료 기준 |
|---|---|---|---|
| S0 | 10/1~10/2 | 이 문서, 결정(9절). 운영 변경 없음 — 10/2는 현 DB·현 코드로 기동 | 결정 |
| S1 전환·분봉 | 10/3~10/5 연휴(휴장) | 아래 목록 | 10/6 첫 거래일 로그·적재 확인, 복원 리허설 통과 |
| S2 상태·관측 | Phase 1(10/19~11/13) | 아래 목록 | — |
| S3 LLM 기억 | Phase 3(11/2~12/11) | 아래 목록 | L2 섀도 기록이 쌓인다 |
| S4 | Phase 7 이후 | 뉴스·감성(sidecar-nlp), 국면 벡터, 교훈 | — |
| 랩 | 상시(장외) | 복원 리허설을 겸하는 랩 컨테이너 | — |

**S1 작업**
1. HA 이미지로 전환한다. compose에 다이제스트를 고정하고 설정을 명시한다.
2. 현 DB를 덤프해 새 이미지에 복원한다.
3. 백업·복원 리허설 스크립트를 고친다. pre/post 복원을 넣고 확장 버전을 기록한다.
4. 테스트 DB를 바꾼다. Testcontainers로 운영 이미지를 쓰고, 외부 DB 주소도 받는다.
5. V11을 추가한다. `minute_bars` 하이퍼테이블과 `daily_bars` 연속 집계다(V10은 종목명 사전 `stock_names`, 2026-10-02 `stock-names.md`).
6. 적재 잡을 DB로 바꾼다. 기동 따라잡기를 넣고 기존 CSV를 옮긴다.
7. ka10080으로 1년치를 되채운 뒤 해당 구간을 refresh한다.
8. pg_stat_statements를 켠다.

**S2 작업**
- F-3 판단 주기 테이블(D-03).
- 알림 대기열(`SKIP LOCKED`). Phase 1 알림 비동기화와 합친다.
- 잡 상태와 느린 질의를 일일 리포트에 싣는다.
- `disclosures`(공시 목록 원문)와 `macro_series`(`available_at` 포함)를 만든다.

**S3 작업**
- `llm_runs`(L1 해설·L2 섀도 기록).
- `knowledge_docs`·`knowledge_chunks`(halfvec + pg_trgm).
- 사후 평가 뷰.
- 임베딩 모델 결정(D-10).

## 9. 결정 요청 (D-15~D-19)

| ID | 질문 | 권고 | 기한 |
|---|---|---|---|
| D-15 | DB 이미지 | timescaledb-ha, 다이제스트 고정 | 10/2 |
| D-16 | PostgreSQL 메이저 버전 | 17 | 10/2 |
| D-17 | 전환 시점 | 10/3~10/5 연휴 | 10/2 |
| D-18 | DB 테스트 환경 | 운영 이미지(Testcontainers) + 외부 DB 주소, 내장 PG(zonky) 제거 | 10/2 |
| D-19 | 학습 실험 위치 | 랩 컨테이너(복원 리허설 겸용) | 10/9 |

### D-15. DB 이미지

- **(a) `timescale/timescaledb-ha` — 권고.**
  - 장점: 확장 96개(툴킷·vectorscale 포함), 최신 pgvector(0.8.6), 공식 문서가 권하는 이미지다.
  - 비용: 870MB이고, 덤프·복원이 필요하다(실측 수 초).
- **(b) `timescale/timescaledb`(alpine).**
  - 장점: 575MB. 데이터 경로·uid·C 라이브러리가 같아, 기동 설정만 바꾸면 기존 볼륨을 그대로 쓸 수 있을 것이다(추론, 미검증).
  - 단점: 툴킷·vectorscale이 없고, pgvector가 0.8.1(HNSW 손상 수정 전)이다.
- **권고 근거:** 사용자 방향("최대 활용")과 pgvector 결함 수정. 덤프·복원 비용이 작다.

### D-16. PostgreSQL 메이저 버전

- **(a) 17 — 권고.**
  - Flyway 11.7.2의 지원 범위 안이다.
  - TimescaleDB 지원이 16보다 길다.
- **(b) 16 유지.**
  - 바뀌는 것이 가장 적다.
  - 단점: TimescaleDB는 PostgreSQL을 커뮤니티 지원 종료보다 일찍 뺀다.
    - 15는 커뮤니티 종료(2027-11)보다 약 1년 4개월 앞선 2.29.0(2026-07-28)에서 빠졌다.
    - 같은 흐름이면 16(커뮤니티 종료 2028-11)은 2027년 중에 빠질 가능성이 높다. 그러면 한 번 더 옮겨야 한다.
- **(c) 18.**
  - 지원이 가장 길다(2030-11).
  - 단점: Flyway 11.7.2가 "시험되지 않음"이라고 경고한다. Boot 4.1 이관(Phase 5) 뒤에 다시 본다.
- **기존 결정과의 충돌:** `upgrade-2026-10/02-research-be.md`는 "16 유지, 18 즉시 업그레이드 금지(pg_upgrade 위험)"였다.
  - 이번에는 어차피 덤프·복원을 하므로 pg_upgrade를 쓰지 않는다.
  - TimescaleDB의 지원 정책이 새 사실이다.

### D-17. 전환 시점

- **(a) 10/3(토)~10/5(월, 휴장) — 권고.**
- **(b) 10/9(금, 휴장)~10/11.**
- **(c) 더 뒤.**
- **권고 근거:**
  - 분봉 1년 창이 매일 하루씩 줄어든다.
  - 연휴 사흘이라 문제가 생겨도 거래일 전에 되돌릴 시간이 있다.

### D-18. DB 테스트 환경

- **(a) 권고.**
  - Testcontainers로 운영 이미지를 쓰고, 외부 DB 주소(`AUTOSTOCK_TEST_DB_URL`)도 받는다.
  - Docker가 없으면 DB 테스트는 건너뛴다. 건너뛴 사실은 로그로 구분한다.
  - 내장 PG(zonky)는 뺀다.
- **(b) 내장 PG 유지 + 조건부 마이그레이션.** TimescaleDB가 없으면 일반 테이블로 만든다. 운영과 다른 스키마를 검증하게 된다.
- **9/30 결정과의 관계:** 9/30 결정은 "Docker가 없어도 DB 테스트가 돈다"였다.
  - 내장 PG에는 TimescaleDB를 올릴 수 없어서 이 결정이 바뀐다.
  - CI(GitHub Actions)에는 Docker가 있어 계속 검증된다. 이미지(870MB)를 받는 만큼 실행 시간이 늘어난다(추정 1분 안팎).

### D-19. 학습 실험 위치

- **(a) 별도 랩 컨테이너 — 권고.**
  - 같은 이미지에 최신 백업을 복원해 만든다.
  - 평소에는 꺼 두고 장외에만 켠다.
  - 월 1회 복원 리허설을 겸한다.
- **(b) 운영 인스턴스 안의 별도 DB.** 자원(CPU·메모리·I/O)을 운영과 나눠 쓴다.
- **(c) 랩 없음.**

## 10. 전환 절차 개요 (S1 — 상세 스크립트는 구현 때)

1. 앱이 꺼져 있는지 확인한다(장외).
2. 기존 `backup_db.ps1`로 덤프한다. 주요 테이블 행 수를 기록한다.
3. 기존 컨테이너를 멈춘다. 볼륨 `pgdata`는 그대로 둔다(롤백용).
4. 새 compose 정의로 기동한다(새 볼륨). healthy가 될 때까지 기다리고, 확장 버전을 확인한다.
5. 덤프를 복원한다(`pg_restore --no-owner --exit-on-error`). 행 수를 대조한다.
6. 앱을 기동한다. Flyway가 V11(하이퍼테이블·연속 집계)을 적용한다.
7. 장외에 되채우기를 하고, 해당 구간을 refresh한다.
8. 새로 백업하고, 새 복원 리허설(pre/post)을 돌린다.

- **롤백:**
  - compose를 이전 정의로 되돌려 기동한다. 옛 볼륨은 전환 직전 상태로 남아 있다.
  - 잃는 것은 전환 뒤에 새로 쌓인 분봉뿐이다. 휴장 기간이라 주문 데이터는 없다.

## 11. 기존 결정과의 관계

| 기존 | 이번 | 처리 |
|---|---|---|
| ADR-1 모놀리스, ADR-2 Kafka 보류, ADR-5 Redis 미도입 | PostgreSQL 올인원 | 유지·보강 |
| 02 BE 조사: "PG 16 유지, 18 즉시 업그레이드 금지" | 17 권고 | 충돌 → D-16 |
| 02 BE 조사: "분봉 월 RANGE 파티셔닝 + pg_partman" | 하이퍼테이블 | 대체(사용자 결정 10/1) |
| `external-data-files.md` 7절: "TimescaleDB 쓰지 않음" | 채택 | 사용자 결정(10/1)으로 변경. 7절 수치는 기준선으로 남긴다 |
| 9/30 DB 테스트 결정: Docker가 없으면 내장 PG | 운영 이미지만 | D-18 |
| D-10 LLM 범위(11/20) | 임베딩 모델·차원·전송 범위 포함 | D-10에 합쳐 결정 |

## 12. 출처

- TimescaleDB `CREATE TABLE … WITH (tsdb.hypertable …)` — 기본값(7일 청크, 컬럼스토어 기본 켜짐, 정책 자동 생성), 2.20.0 도입: https://www.tigerdata.com/docs/reference/timescaledb/hypertables/create_table
- 컬럼스토어 정책: https://www.tigerdata.com/docs/reference/timescaledb/hypercore/add_columnstore_policy
- 논리 백업(pre/post restore, `-j` 금지, 덤프 경고): https://www.tigerdata.com/docs/deploy/self-hosted/backup-and-restore/logical-backup
- 설치(HA 이미지 권장, `PGDATA`): https://www.tigerdata.com/docs/get-started/choose-your-path/install-timescaledb
- HA 이미지 볼륨 권한·`PGDATA`(uid 1000): https://github.com/timescale/timescaledb-docker-ha/issues/620
- HA 이미지 구성(Ubuntu, 확장 목록): https://github.com/timescale/timescaledb-docker-ha
- alpine 이미지 구성(pgvector v0.8.1): https://github.com/timescale/timescaledb-docker
- TimescaleDB 변경 기록(2.29.0 PostgreSQL 15 제거, 2.30.2): https://github.com/timescale/timescaledb/blob/main/CHANGELOG.md
- Docker Hub 태그·크기: https://hub.docker.com/r/timescale/timescaledb-ha/tags , https://hub.docker.com/r/timescale/timescaledb/tags
- pgvector(반복 스캔, 차원 한도, halfvec): https://github.com/pgvector/pgvector
- pgvector 변경 기록(0.8.3 HNSW vacuum 손상 수정, 0.8.6): https://github.com/pgvector/pgvector/blob/master/CHANGELOG.md
- pgai 유지보수 중단(2026-02): https://github.com/timescale/pgai
- Spring AI 2.0 GA(Boot 4.0/4.1용): https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/
- PostgreSQL 버전별 지원 종료일(15: 2027-11-11, 16: 2028-11-09, 17: 2029-11-08, 18: 2030-11-14): https://www.postgresql.org/support/versioning/
- 내부: `external-data-files.md` 7절(일반 테이블 기준선), `upgrade-2026-10/02-research-be.md`(PG 버전·파티셔닝), `upgrade-2026-10/12-decisions.md`(D-10), PLAN.md ADR-1·2·5

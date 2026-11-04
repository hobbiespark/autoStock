# PostgreSQL 18 후보 이미지 검증 (VALUNIVO 4-5 U6)

- 기준일: 2026-11-04. 운영은 PG17.11·TimescaleDB 2.30.2다. 이번 변경은 운영 DB 전환이 아니다.
- 시험 도구 `PostgresTestDatabase`는 기본 운영 이미지 `IMAGE`를 유지한다. 판 올림 검증 때만 `AUTOSTOCK_TEST_DB_IMAGE`로 후보를 지정한다. `timescale/timescaledb-ha@sha256:<64자리>`만 허용하며, 실제 사용한 다이제스트는 시험 로그에 남긴다. 외부 DB URL을 지정하면 기존 외부 DB 경로가 우선한다.
- 이번 후보: `timescale/timescaledb-ha:pg18.6-ts2.30.2@sha256:0b8a528ee896040750825e274e244496a80afbfa1ad9321181460fb089a40017`.
- JDK 25, 후보 이미지 환경변수와 `AUTOSTOCK_TEST_DB_REQUIRED=true`, `gradlew.bat test --rerun`: 1296건, 실패·오류 0, 건너뜀 23. 구조 시험 11건 통과. Testcontainers가 후보 다이제스트로 기동한 로그 확인.
- 같은 운영 덤프를 네트워크 없는 PG17·PG18 컨테이너에 각각 복원: 표 31개·563939행, 시퀀스·Flyway 이력·청크·압축·작업 정책 일치. 자세한 근거와 다음 운영 전환 조건은 VALUNIVO `aiDoc/implementation/583-autostock-u6-pg18-rehearsal.md`.
- 다음: 시험 기본 이미지·인프라 프로필의 동시 변경, 앱 쓰기 정지 후 최종 덤프, 새 PG18 볼륨 복원, 앱 기동·백업 대상 확인. 기존 PG17 볼륨과 최종 덤프를 유지하고 실패 시 되돌린다. 운영 기동은 기존 앱 권한 제한을 따른다.

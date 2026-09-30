# 06. 인프라 관점 조사 — 실행 환경·관측·데이터 보호·보안·CI/CD (2026-10-01)

> 문서 묶음: [00-README](00-README.md) · [01 현황](01-current-state.md) · 조사 [02 BE](02-research-be.md) · [03 FE](03-research-fe.md) · [04 디자인](04-research-design.md) · [05 기획](05-research-planning.md) · [06 인프라](06-research-infra.md) · [07 주식거래①시장·제도](07-research-trading-market.md) · [08 주식거래②전략·LLM](08-research-trading-strategy.md) · 감사 [09 BE](09-audit-be.md) · [10 FE](10-audit-fe.md) · **[11 실행 계획](11-execution-plan.md)** · [12 결정 목록](12-decisions.md)


> 작성: SRE/DevOps 겸 PM 관점. 20분 타임박스 조사. 각 항목은 **사실 → 출처 → 함의·권고·비용·난이도** 순.
> 표기: ✅ 1차 자료로 확인 / ⚠️ 일반 지식·2차 자료 (재확인 권장) / ❓ 미확인.
> 대상: Java 21 / Spring Boot 3.5 / PostgreSQL 16(Docker) / React, Windows 11 PC 1대 24h, 키움 REST API, 예산 월 수만 원 이하.

---

## 보강 확인·정정 (2026-10-01)

| 항목 | 결과 | 근거 |
|---|---|---|
| 키움 허용 IP | **등록 필수, 최대 10개**. IP 변경 시 `8010`, 단말 미인증 `8040/8050/8103` | [서비스 안내](https://openapi.kiwoom.com/intro/serviceInfo), [알고랩 키 가이드](https://algolab.co.kr/kiwoom-rest-key-guide), 공식 스펙 JSON |
| 키움 자동 해지 | 3개월 미접속 시 매월 첫 영업일 해지, **접속 기준은 실서버 — 모의만 쓰면 해지될 수 있음** | [키움 소개](https://openapi.kiwoom.com/intro?dummyVal=0) |
| 키움 호출 한도 | 실전 주문 5회/초·조회 5회/초, 모의 토큰별 TR당 1회/초, 토큰 24시간 | 동상 |
| Docker Desktop | 로그인 없이 기동 **공식 미지원** | [docker/roadmap#515](https://github.com/docker/roadmap/issues/515) |
| Healthchecks.io | Telegram 통합 **공식 제공**, 무료 20체크 | [통합 페이지](https://healthchecks.io/integrations/telegram/), [가격](https://healthchecks.io/pricing/) |
| WinSW | 안정 v2.12.0(2025-01-28), v3.0.0-alpha.11(2025-01-29) — 사용 가능하나 활동 느림 | [릴리스](https://github.com/winsw/winsw/releases) |
| 세무 정정 | 거래세 2026 세율(0.20%), 대주주 50억 유지, 금투세 폐지 확정 | 7.7절 본문 정정 |
| 기존 결정과 다른 권고 | JNA 절전 차단 제거 권고 → **유지**(9/30 사용자 결정·구현, powercfg와 병행 = 심층 방어). CI 키움 시크릿 제거 권고 → **모의 키 유지**(기존 결정) | [11 실행 계획](11-execution-plan.md) 충돌 해소표 |

기획(PM) 관점 내용(원문 7절)은 [05 기획](05-research-planning.md)으로 옮겨 확장했다.

## 0. 요약 (한 줄)

- 가장 큰 리스크는 "실행 환경(절전·재부팅·단일 PC)"과 "백업 없음"이며, 둘 다 월 0~1.5만 원과 1~2일 작업으로 해결 가능.
- 키움 REST API의 **해외/클라우드 IP 차단 여부는 공식 문서에서 확인되지 않음(❓)** → 클라우드 이전 전 반드시 실측(모의서버에서 토큰 발급·조회 TR 성공 여부) 필요.
- 실전 전환 전 필수: 백업+복구 리허설, dead-man switch, 스케줄 폭주 방지(재시작 idempotency + jitter), 키 분리(paper/live), 손실 한도 서킷브레이커.

---

## 1. 실행 환경 비교

### 1.1 키움 REST API 제약 (공통 전제)

- ✅ 사실: 키움 REST API 호출 한도 — 국내주식 주문 TR 1초당 5회, 조회 TR 1초당 5회; 모의투자 계좌는 TR별·계좌별 1초당 1회. 3개월 미접속 시 매월 첫 영업일 자동 해지 — **보강 확인: "접속 기준은 실서버 기준이며, 모의투자 서버만 이용하는 경우 자동 해지될 수 있습니다"**([키움 소개](https://openapi.kiwoom.com/intro?dummyVal=0)). 장기 모의 운영 중 서비스가 해지될 수 있으므로 대응 필요(12-decisions D-02). 2026-09-18 `8001` 키 무효화 장애의 유력 원인 후보(추론). 계좌 보유 + HTS ID 연결 고객만 사용 등록 가능.
  - 출처: https://openapi.kiwoom.com/intro?dummyVal=0
- ✅ 사실: 공식 GitHub(Kiwoom-Securities/Kiwoom-REST-API)는 실전(real)과 모의(demo) 키가 서로 다르다고 명시. App Key/Secret 노출 금지 강조.
  - 출처: https://github.com/Kiwoom-Securities/Kiwoom-REST-API
- ✅ 사실: 접근토큰 유효기간 24시간, 매 24시간마다 재발급 필요.
  - 출처: https://openapi.kiwoom.com/intro/serviceInfo
  - 함의: "키 무효화" 사고의 한 원인 후보. 토큰 만료 60분 전 선제 갱신 + 401 수신 시 1회 재발급 후 재시도(재발급도 rate limit 대상이므로 단일 뮤텍스로 직렬화). 재발급 실패 연속 3회 → P1 + STANDBY.
- ✅ 보강 확인: **허용 IP 등록이 필수이며 최대 10개**("인증정보 탈취 방지 목적", [서비스 안내](https://openapi.kiwoom.com/intro/serviceInfo)). 공인 IP가 바뀌면 토큰이 `8010`(발급 IP ≠ 요청 IP)으로 거절되고, 미등록 단말은 `8040/8050/8103`(단말기 인증 실패) 계열 오류(공식 스펙 오류코드). **새 실행 호스트(미니PC·클라우드)는 IP 등록이 선행조건**.
- ❓ 여전히 미확인: **해외 IP·클라우드 IP 차단 여부**. 공식 소개 페이지·공식 GitHub·공지 검색에서 IP/국가 제한 문구를 찾지 못함. (구 OpenAPI+는 Windows OCX 전용이라 사실상 국내 PC 강제였으나, REST는 별도 규정 미확인.)
  - 함의: "차단 없음"을 가정하지 말 것. 클라우드 이전 결정 전에 **모의서버에서 토큰 발급 + 조회 TR + 웹소켓 접속을 실측**하고, 결과를 ADR로 기록. 증권사 정책은 예고 없이 바뀔 수 있으므로 클라우드 채택 시에도 국내 PC를 폴백으로 유지.
- ⚠️ 함의: 모의투자 1초 1회 제한 때문에 "재연결 폭주/스케줄 폭주"가 곧 429·차단으로 이어짐 → 전역 rate limiter(token bucket, 주문/조회 별도 버킷)와 재시도 jitter는 환경과 무관하게 필수.

### 1.2 옵션 비교

| 옵션 | 월 비용(대략) | 난이도 | 장점 | 단점/리스크 |
|---|---|---|---|---|
| A. 현재 Windows PC 하드닝 | 전기료 외 0 | 낮음 | 즉시 적용, 키움 IP 이슈 없음 | Windows Update 재부팅, 절전, 가정 네트워크/정전 단일점 |
| B. 미니PC(N100급) + Linux | 초기 15~25만 원, 월 전기 ~2천 원(⚠️ 10W 기준 추정) | 중 | systemd·cron·Docker 정식 지원, 무인운영에 최적, 국내 IP | 초기 비용, 하드웨어 관리 |
| C. AWS Lightsail 서울 | $5(0.5GB)·$7(1GB)·$12(2GB) ✅ | 중 | 관리형, 스냅샷 백업, 고정 IP | JVM+PG 위해 2GB 이상 권장($12≈1.7만 원), **키움 IP 차단 ❓** |
| D. Oracle Cloud Always Free (춘천 리전) | 0 | 중~상 | 무료 ARM VM | ✅ 2026-06-15부로 A1 무료 한도 4 OCPU/24GB → **2 OCPU/12GB**로 축소(무공지). 용량 부족(Out of capacity) 빈번, 유휴 회수 정책 존재(⚠️), **키움 IP 차단 ❓** |
| E. Vultr 서울 | $6~/월(⚠️ 미확인) | 중 | 저가, 서울 리전 | 키움 IP 차단 ❓, 검증 안 함 |
| F. 홈 NAS(Synology/QNAP Docker) | 기존 보유 시 0 | 중 | 상시 가동 설계, 국내 IP, 백업 통합 | 저사양 모델은 JVM 무리, Docker 지원 모델 한정 |

- 출처(C): https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-bundles.html (Nano-0.5GB $5, Micro-1GB $7, Small-2GB $12, Medium-4GB $24; 서울 리전 지원 여부는 해당 페이지 미기재 → ⚠️ 서울 리전은 존재하나 페이지에서 미확인)
- 출처(D): https://www.infoq.com/news/2026/07/oracle-cloud-free-tier-limits/

### 1.3 Windows PC 하드닝 (옵션 A, 즉시 적용 권고)

⚠️ 아래는 Windows 표준 기능에 대한 일반 지식(공식 문서 재확인 권장).

- `powercfg`: 전원 옵션 "고성능/절전 안 함". 명령 예: `powercfg /change standby-timeout-ac 0`, `powercfg /change hibernate-timeout-ac 0`, `powercfg /hibernate off`. 추가로 "네트워크 어댑터 전원 관리 → 전원 절약을 위해 이 장치를 끌 수 있음" 해제.
  - 함의: **JNA로 SetThreadExecutionState 호출하는 현재 방식은 OS 설정으로 대체 가능**하며, OS 설정이 더 단순·신뢰성 높음. JNA는 폴백으로만 유지하거나 제거.
- Windows Update 재부팅 제어: 설정 → "사용 시간(Active hours)" 최대 18시간 지정(예: 06:00~24:00), 그룹 정책 `로그온한 사용자가 있을 때 자동 업데이트 설치 시 자동 다시 시작 안 함`(Pro 에디션 필요). Home 에디션은 정책 편집기 부재 → 레지스트리 `HKLM\SOFTWARE\Policies\Microsoft\Windows\WindowsUpdate\AU\NoAutoRebootWithLoggedOnUsers=1`(⚠️).
  - 권고: 업데이트는 "장외 시간 + 수동 승인"으로 고정. 화요일(패치 화요일 익일 KST 수요일 새벽) 이후 주말에 수동 설치.
- 서비스화: Task Scheduler("로그온 여부와 관계없이 실행", "시작 시", 실패 시 재시작) 또는 **WinSW**(Java 친화, XML 하나로 서비스 등록, stdout 로테이션) / NSSM. 현재 "로그인 시 자동시작 bat"은 로그인 세션 종속 → 로그오프·화면 잠금·재부팅 후 자동 로그인 부재 시 미실행.
  - 권고: WinSW로 `gradlew bootRun` 대신 **빌드된 jar(`java -jar`)** 실행. bootRun은 Gradle 데몬·소스 컴파일 의존이라 무인 운영에 부적합.
- Docker Desktop 제약: ✅ Windows 11 23H2 이상·WSL 2.1.5 이상·8GB RAM 요구. 개인 사용 무료. **보강 확인: 로그인 없이 기동은 공식 미지원** — Docker 로드맵 이슈 #515가 open, Docker 측은 "Docker Desktop은 개발용, 서버는 Docker Engine" 입장. 커뮤니티는 Task Scheduler(S4U)로 우회하나 비공식([docker/roadmap#515](https://github.com/docker/roadmap/issues/515)).
  - 출처: https://docs.docker.com/desktop/setup/install/windows-install/
  - 함의: PG를 Docker Desktop에 두면 "자동 로그인 → Docker Desktop 기동 → 앱 기동" 순서 의존이 생김. 대안: (a) Windows용 PostgreSQL 네이티브 설치를 서비스로, (b) WSL2 안에서 `dockerd` 직접 실행 + Task Scheduler로 `wsl -d Ubuntu -e ...` 부팅 기동, (c) 옵션 B/C로 이전.

### 1.4 권고

1. **단기(이번 주)**: 옵션 A 하드닝 — powercfg, Active hours, WinSW 서비스화, jar 실행, PG 네이티브 서비스 또는 WSL2 dockerd. 비용 0, 난이도 낮음.
2. **중기(1~2개월)**: 옵션 B 미니PC Linux로 이전(초기 20만 원 내외). systemd unit + Docker Compose + `Restart=always`. 키움 IP 이슈가 없고 예산 내 가장 안정적.
3. 클라우드(C/D)는 **키움 IP 차단 실측 후** 대시보드·모니터링·백업 저장소 용도로 우선 검토(매매 엔진은 국내 PC 유지).

---

## 2. 컨테이너화

- ⚠️ 앱 이미지: Spring Boot 3.x `./gradlew bootBuildImage`(Cloud Native Buildpacks, Docker 데몬 필요) 또는 Jib(`gradle jib`, 데몬 불필요·레이어 캐시 우수·CI 친화). Java 21 + `eclipse-temurin:21-jre` 계열 베이스.
  - 권고: CI에서 Jib로 GHCR 푸시 → 실행 호스트는 `docker compose pull && up -d`. 난이도 낮~중.
- ⚠️ healthcheck: Spring Boot Actuator `/actuator/health`(liveness/readiness 분리: `management.endpoint.health.probes.enabled=true`). Compose `healthcheck: test: ["CMD","curl","-f","http://localhost:8080/actuator/health/liveness"]`, `interval: 30s`, `start_period: 60s`. PG는 `pg_isready -U ...`. 앱 서비스는 `depends_on: db: condition: service_healthy`.
- ⚠️ restart 정책: `restart: unless-stopped`. **주의**: 재시작 시 "스케줄 폭주" 재현 가능 → 앱 기동 시 `@Scheduled` misfire 정책 명확화(놓친 작업 건너뛰기), 기동 후 warm-up 지연, 장중 재시작 시 포지션 재동기화(REST로 잔고·미체결 조회) 후 매매 재개.
- 절전 차단 대안: 컨테이너 환경(Linux)에서는 절전 개념 자체가 없음(`systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target`). Windows에 남는다면 1.3의 powercfg로 대체. JNA 의존 제거 가능.
- Windows Docker Desktop 제약: 1.3 참조. 추가로 WSL2 VM의 메모리 기본 상한(호스트 50%)과 시계 드리프트(절전 복귀 후 WSL 시계 어긋남 ⚠️ 알려진 이슈) → **절전 복귀 후 "스케줄 폭주"의 한 원인일 수 있음**. 확인: `wsl -d Ubuntu date` vs 호스트 시각 비교, 필요 시 `hwclock -s`.
- 비용 0, 난이도 중(하루).

---

## 3. 관측·알림

### 3.1 Dead-man switch (최우선)

- ✅ Healthchecks.io: 무료 Hobbyist 플랜 20개 체크, 체크당 로그 100건, SMS 미포함. Supporter $5/월(동일 한도), Business $20/월(100체크). **보강 확인: Telegram 통합 공식 제공**([healthchecks.io/integrations/telegram](https://healthchecks.io/integrations/telegram/)).
  - 출처: https://healthchecks.io/pricing/ , https://healthchecks.io/docs/configuring_notifications/
- ✅ Uptime Kuma: 셀프호스트 오픈소스, 모니터 타입에 "Push"(heartbeat 수신형 = dead-man switch) 포함, 알림 채널에 Telegram 명시(90+ 서비스).
  - 출처: https://github.com/louislam/uptime-kuma 단 **같은 PC에 두면 PC가 죽을 때 함께 죽음** → 외부(Healthchecks.io 무료 또는 $5 VM)에 두는 것이 원칙.
- 권고: 앱의 STANDBY/ACTIVE 상태머신에서 **1분마다 Healthchecks.io ping**(ACTIVE 중), 장중 3분 무응답 시 텔레그램 알림. 장외(STANDBY)는 별도 체크(15분 주기)로 분리. 비용 0, 난이도 낮음(반나절).

### 3.2 메트릭 최소 구성

- ⚠️ Spring Boot Actuator + Micrometer Prometheus 레지스트리(`/actuator/prometheus`) → Prometheus(15s scrape, 보존 15일) → Grafana(Docker, 로컬). 개인 1대 기준 RAM 400~600MB 추가. 무료.
- 핵심 커스텀 메트릭: 키움 API 호출 수/429 수/지연, 주문 제출·체결·거부 수, 미체결 수, DB 풀 사용률(HikariCP는 기본 노출), 스케줄 실행 시간·미스파이어, 마지막 시세 수신 시각(gauge), 일중 손익.
- 알림은 Grafana Alerting → Telegram 컨택트 포인트(내장). Prometheus Alertmanager는 생략 가능.
- 대안(더 가벼움): 메트릭 없이 로그 + Healthchecks.io + 텔레그램만으로 시작하고, 사고 2회 이상 재발 시 Prometheus 도입(기능 크리프 방지).

### 3.3 텔레그램 알림 등급

| 등급 | 예 | 채널/동작 |
|---|---|---|
| P1 CRITICAL | 주문 거부 연속, 토큰 무효화, DB 연결 실패, dead-man 미응답, 일손실 한도 도달 | 즉시 전송 + 자동 STANDBY 전환(신규 주문 중단) |
| P2 WARN | 429 증가, 재연결 3회 이상, 풀 사용률 80% | 5분 집계 후 1건 |
| P3 INFO | 체결 요약, 일일 리포트, 기동/종료 | 일 1~3회 배치 |

- 2단계 확인: 봇 명령(`/resume`, `/kill`)은 **명령 → 6자리 확인 코드 응답** 또는 인라인 버튼 "확인" 2단계. 발신 chat_id 화이트리스트 필수. 실전에서는 "매매 재개"만 2단계, "긴급정지"는 1단계(안전 쪽으로 빠르게).
- 알림 폭주 방지: 동일 키 알림 10분 억제(dedupe), 시간당 최대 N건.

### 3.4 로그 로테이션

- ⚠️ Logback `SizeAndTimeBasedRollingPolicy`(일별 + 50MB, 총 상한 2GB, 14~30일 보존). Docker는 `logging: driver: json-file, options: max-size: 50m, max-file: "5"`. 주문·체결 로그는 별도 appender로 **90일 이상**(세무·분쟁 대비, 4절 참조).

---

## 4. 데이터 보호

- 현재 "백업 없음"은 실전 전환 시 **차단(blocking) 이슈**.
- ⚠️ pg_dump 일일 백업: `pg_dump -Fc -U autostock autostock > backup_$(date +%F).dump`(custom 포맷, 압축, 선택 복원 가능). Docker: `docker exec pg pg_dump -Fc ...`. 스케줄: 장 마감 후 16:00 + 자정. 보존: 일 7 + 주 4 + 월 12(GFS). 오프사이트 복사: rclone → 클라우드 오브젝트 스토리지(Backblaze B2/Cloudflare R2 등 수 GB는 무료~수백 원 ⚠️).
  - **복구 리허설 없는 백업은 백업이 아님**: 월 1회 `pg_restore`로 빈 DB에 복원 → row count 대조. 이를 완료의 정의에 포함.
- RTO/RPO 목표(제안): RPO ≤ 1영업일(일일 dump) — 주문·체결 원장은 증권사가 원본이므로 재동기화 가능; RTO ≤ 4시간(장중이면 "매매 중단, 익일 재개"가 기본 대응). 실전 초기엔 이 수준이면 충분, 자금 규모 커지면 WAL 아카이빙(pgBackRest)으로 RPO 5분.
- 보존 기간(제안): 주문/체결/이벤트 테이블 **5년 이상 원본 보존**(세무 대응·분쟁), 시그널/LLM 판단 로그 1년, 애플리케이션 로그 30일, 메트릭 15일.
- 분봉 축적 용량: ⚠️ 추정 — 1분봉 1행 ≈ 60~100B(인덱스 포함). 종목 100개 × 390분/일(정규장 09:00~15:30) × 250일 ≈ 975만 행/년 ≈ **1GB/년 내외**. 종목 1,000개면 ~10GB/년. → 1~2년은 파티셔닝 불필요; 100종목 초과·2년 초과 시 **월 단위 RANGE 파티셔닝**(`PARTITION BY RANGE (bar_time)`) + BRIN 인덱스, 오래된 파티션은 detach → CSV/Parquet 아카이브.
- CSV 데이터 버전 관리: 리포에 대용량 CSV 커밋 금지. 옵션: DVC(원격은 로컬 디스크/오브젝트 스토리지), 또는 단순히 `data/` 를 `.gitignore` + 체크섬 매니페스트(`sha256sum > manifest.txt`)를 git에 커밋. 백테스트 재현성엔 "데이터 스냅샷 해시 + 전략 커밋 해시 + 파라미터"를 리포트에 기록하는 것이 핵심.
- 비용 0~1천 원/월, 난이도 낮음(하루).

---

## 5. 시크릿·보안

### 5.1 2025~2026 공급망 사고 교훈

- ✅ tj-actions/changed-files(CVE-2025-30066, 2025-03-14~15): 공격자가 봇 PAT를 탈취해 **기존 버전 태그를 악성 커밋으로 이동**, 러너 메모리를 덤프해 워크플로 시크릿(AWS 키, GitHub PAT, npm 토큰, RSA 키)을 공개 로그에 노출. 권고: 액션을 **커밋 SHA로 고정**, PAT 권한 최소화, 조직/리포 수준 허용 액션 목록.
  - 출처: https://www.wiz.io/blog/github-action-tj-actions-changed-files-supply-chain-attack-cve-2025-30066 , https://github.com/advisories/ghsa-mrrh-fwg8-r2c3
- 함의: CI가 **실거래(LIVE) 키**를 알 이유는 없다. 배포용 시크릿이 필요하면 환경(environment) 보호 규칙 + 별도 잡.
- **실행 계획 반영(충돌 해소)**: 리포의 기존 결정은 `KIWOOM_MOCK_G_*`(모의 일반 계좌)를 CI 스모크에 쓰고 `KIWOOM_LIVE_*`는 CI 금지다(ci.yml 주석, PROGRESS 5절). 모의 키는 실자금을 움직일 수 없으므로 **기존 결정을 유지**하고, 대신 액션 SHA 고정·`permissions: contents: read`·포크 PR 비전달(현행 자동)로 노출면을 줄인다. 또한 CI에서 모의 서버 토큰을 발급하면 **토큰 IP 바인딩·허용 IP 등록**과 충돌할 수 있어(러너 IP는 매번 다름 — 추론) 스모크가 `8040/8050`으로 실패하면 스모크를 수동 트리거 전용으로 옮긴다.

### 5.2 GitHub Actions 하드닝 (권고 설정)

- 워크플로 최상단 `permissions: contents: read`(기본 토큰 읽기 전용), 잡별로 필요한 권한만 추가.
- 모든 `uses:`를 40자 SHA로 고정 + 주석에 버전(`# v4.2.2`). Dependabot `package-ecosystem: github-actions`로 SHA 자동 갱신 PR.
- `pull_request_target` 사용 금지(포크 PR에서 시크릿 노출 경로). 리포 설정 → Actions → "Allow select actions"(GitHub 제작 + 명시 목록).
- Dependabot: gradle 생태계도 등록(주간). 보안 알림 켬.
- 난이도 낮음(1~2시간), 비용 0.

### 5.3 로컬 시크릿

- `.env` ACL(Windows): `icacls .env /inheritance:r /grant:r "%USERNAME%:R"`(⚠️) — 본인 계정 읽기 전용, 상속 제거. `.gitignore`에 `.env` 확인 + **gitleaks pre-commit 훅**(`gitleaks protect --staged`) + CI에서 `gitleaks detect`(SHA 고정).
- DPAPI / Windows Credential Manager: 사용자 계정에 바인딩된 암호화, Java에서 JNA·`cmdkey` 경유로 접근 가능하나 구현 부담 대비 이득 작음(같은 사용자 세션의 악성코드는 어차피 복호화 가능). 개인 1대 환경에선 **`.env` ACL + 디스크 암호화(BitLocker)** 조합이 실용적.
- SOPS + age: 암호화된 `.env.enc`를 리포에 커밋 가능, 복호화 키(age private key)는 PC 로컬에만. 다중 머신·백업·재설치 시 유리. 난이도 낮음. 권고: 미니PC 이전 시 도입.
- 키 노출 대응 플레이북: (1) 키움 API 관리 페이지에서 즉시 키 폐기·재발급 (2) 앱 STANDBY 강제 (3) 노출 범위 조사(git 히스토리 `gitleaks detect --log-opts`, 로그, 스크린샷) (4) git 히스토리 정리 시 `git filter-repo` 후 force push + 협력자 재클론 (5) 포스트모템 작성(8절 템플릿). **키움 키 자체의 만료·회전 주기는 미확인(❓)** → 분기 1회 수동 회전을 정책으로.
- 실전 키 격리: 실전 App Key는 paper 환경 프로세스에서 **읽을 수조차 없게** 파일·프로파일 분리(`.env.live`는 live 프로파일에서만 로드, 파일 존재 여부를 기동 시 검증).

---

## 6. CI/CD

- ⚠️ Gradle: `gradle/actions/setup-gradle`(SHA 고정)이 의존성·빌드 캐시 자동 관리. `org.gradle.configuration-cache=true`(Gradle 8.x, 반복 빌드 구성 단계 스킵), `org.gradle.caching=true`. CI 시간 30~60% 단축 기대(⚠️ 프로젝트별 상이).
- 테스트 분리: `test`(단위, PR마다) / `integrationTest`(Testcontainers PostgreSQL, main 머지·야간) / `contractTest`(키움 모의 API 스모크, **수동 트리거만**, 1초 1회 제한 준수). JUnit 태그(`@Tag("integration")`) + Gradle 소스셋.
- 릴리스 태깅: SemVer `v1.4.0`, 태그 푸시 → 릴리스 워크플로(Jib 이미지 빌드 → GHCR `:v1.4.0` + `:sha-abcdef`). `:latest` 사용 금지(롤백 명확성).
- 배포 창: **평일 15:40~다음날 08:30, 주말** 만 허용. 워크플로에서 KST 시각 검사 스텝으로 장중 배포 차단(`workflow_dispatch` 입력 `force=true`로만 우회). 화·수 주말 모의서버 점검 시간대(❓ 정확한 점검 시간 미확인 — openapi.kiwoom.com "시스템작업알림" 확인)와 겹치지 않게.
- 롤백: 이미지 태그 되돌림(`docker compose` 환경변수 `APP_TAG`) + DB 마이그레이션은 **Flyway 전진만(forward-only)**, 파괴적 변경은 2단계(expand→contract). 롤백 절차를 런북에 명시하고 분기 1회 연습.
- paper→live 분리: Spring 프로파일 `paper`/`live`, 별도 DB(또는 스키마), 별도 `.env`, 별도 텔레그램 채널, live는 **태그된 릴리스만** 배포 가능(브랜치 빌드 금지). 기동 시 배너에 모드 대문자 표시 + 텔레그램 기동 알림에 모드 포함.
- 비용 0(퍼블릭 리포 Actions 무료), 난이도 중(1~2일).

---

## 8. 문서화

### 8.1 MADR 4.0 ADR 템플릿

- ✅ MADR 4.0.0(2024-09-17 릴리스). 프론트매터: `status`, `date`, `decision-makers`, `consulted`, `informed`. 섹션: 제목 / Context and Problem Statement / Decision Drivers / Considered Options / Decision Outcome / Consequences / Confirmation / Pros and Cons of the Options / More Information. 최소 템플릿은 제목·Context·Considered Options·Decision Outcome 4개.
  - 출처: https://adr.github.io/madr/ , https://github.com/adr/madr/releases
- 권고: `docs/adr/NNNN-title.md`, 최소 템플릿으로 시작. 이 조사에서 곧바로 쓸 ADR: (1) 실행 환경 선택 (2) 키움 클라우드 IP 실측 결과 (3) 백업 정책·RPO/RTO (4) paper/live 분리 방식 (5) JNA 절전차단 제거.

```markdown
---
status: proposed | accepted | deprecated | superseded by ADR-000X
date: 2026-10-01
decision-makers: 데브
consulted: -
informed: -
---
# {문제와 해법을 담은 짧은 제목}

## Context and Problem Statement
## Decision Drivers
## Considered Options
## Decision Outcome
### Consequences
### Confirmation
## Pros and Cons of the Options
## More Information
```

### 8.2 사고 플레이북 템플릿 (`docs/runbooks/<증상>.md`)

```markdown
# 플레이북: {증상 — 예: 장중 heartbeat 3분 무응답}
- 심각도: P1 | 최초 대응 시한: 5분
## 1. 즉시 조치 (자금 보호 우선)
- [ ] 텔레그램 /pause 또는 프로세스 STANDBY 강제
- [ ] 키움 HTS/MTS에서 미체결 확인·필요 시 수동 취소
## 2. 진단
- 확인 명령/로그 위치/대시보드 패널
## 3. 복구
- 단계별 명령, 재시작 시 상태 재동기화 확인 방법
## 4. 종료 조건
- 정상 판정 기준(heartbeat 재개, 미체결 0 또는 의도된 상태)
## 5. 후속
- 포스트모템 필요 여부(P1은 항상), 티켓 링크
```

실측 사고 기준 우선 작성할 플레이북: (a) 절전/재부팅 후 미기동 (b) 재시작 후 스케줄 폭주·풀 고갈 (c) 모의서버 점검 중 재연결 폭주 (d) API 키 무효화(401) (e) DB 연결 실패 (f) 키 노출.

### 8.3 포스트모템 템플릿 (블레임리스, `docs/postmortems/YYYY-MM-DD-<slug>.md`)

```markdown
# 포스트모템: {제목}
- 일시(KST) / 영향 시간 / 심각도 / 작성자 / 상태(초안·완료)
## 요약 (3줄)
## 영향 (자금·미체결·놓친 신호·데이터)
## 타임라인 (KST, 감지→대응→복구)
## 근본 원인 (5 Whys)
## 잘된 점 / 운이 좋았던 점 / 안 된 점
## 액션 아이템 (담당·기한·게이트 연계) — 각 항목은 "재발 방지" 또는 "감지 단축"으로 분류
## 교훈
```

과거 4건 사고(절전 정지, 절전 복귀 폭주, 주말 점검 재연결 폭주, 키 무효화)를 **소급 포스트모템**으로 작성 → 액션 아이템이 곧 G0 백로그.

---

## 9. 권고 Top 10

| # | 권고 | 영향 | 난이도 | 월 비용 | 기존 결정과 충돌 |
|---|---|---|---|---|---|
| 1 | 외부 dead-man switch(Healthchecks.io 무료) + 텔레그램 P1 알림 활성화 | 매우 높음 | 낮음 | 0 | 없음(텔레그램 미활성 → 활성) |
| 2 | 일일 pg_dump + 오프사이트 복사 + 월 복구 리허설 | 매우 높음 | 낮음 | 0~1천 원 | 없음 |
| 3 | 재시작 idempotency: 기동 시 상태 재동기화, 놓친 스케줄 스킵, 재연결 지수 백오프+jitter, 글로벌 rate limiter | 매우 높음 | 중 | 0 | 없음(사고 직접 해결) |
| 4 | Windows 하드닝: powercfg 절전 off, Active hours, Update 수동, WinSW 서비스 + jar 실행 | 높음 | 낮음 | 0 | **JNA 절전차단·bat 자동시작·bootRun 대체** |
| 5 | 전략 독립 리스크 가드(일손실·노출·주문크기 한도 → 자동 STANDBY) | 매우 높음(실전) | 중 | 0 | 없음 |
| 6 | paper/live 프로파일·DB·시크릿·채널 완전 분리, live는 태그 릴리스만 | 높음 | 중 | 0 | 없음 |
| 7 | GitHub Actions 하드닝(SHA 핀, permissions 최소, Dependabot, gitleaks) + CI에서 실거래 시크릿 제거 | 중 | 낮음 | 0 | **GitHub Actions 시크릿 사용 관행 변경** |
| 8 | 미니PC Linux + Docker Compose로 이전(중기) | 높음 | 중 | 초기 20만 원, 전기 ~2천 원 | **Windows PC 단일 실행 결정 변경**, Docker Desktop 의존 제거 |
| 9 | 키움 REST 클라우드/해외 IP 실측 → ADR 기록(클라우드 채택 여부 결정) | 중 | 낮음 | 0(실험) | 없음 |
| 10 | 소급 포스트모템 4건 + 플레이북 6건 + ADR 5건 작성, 게이트 로드맵 채택 | 중 | 낮음 | 0 | 없음 |

- Prometheus+Grafana는 Top 10에서 제외(1·3 완료 후 재발 시 도입 — 기능 크리프 방지).

---

## 10. 실전 전환 전 필수 체크리스트

**자금 안전**
- [ ] 실전 App Key는 live 프로파일에서만 로드되며 paper 프로세스는 파일 자체에 접근 불가
- [ ] 일손실 한도·총 노출 상한·주문 1건 최대 금액이 코드로 강제되고, 한도 도달 시 자동 STANDBY 테스트 통과
- [ ] "긴급 정지" 경로 2개 이상(텔레그램 `/kill`, 로컬 킬 스위치 파일/명령) 및 HTS/MTS 수동 취소 절차 숙지
- [ ] 초기 자금 = 전액 손실 감수 가능 금액으로 제한(G1)

**운영 안정성**
- [ ] paper 환경 30영업일 무중단(계획 재시작 제외) 달성
- [ ] 외부 dead-man switch 작동, P1 알림 60초 내 도달 실측
- [ ] 절전·재부팅·네트워크 단절·모의서버 점검 4개 시나리오를 **의도적으로 재현**하여 복구 확인(카오스 리허설)
- [ ] 재시작 시 잔고·미체결 재동기화 및 놓친 스케줄 스킵 동작 확인
- [ ] 키움 rate limit(주문 5/s, 조회 5/s) 대비 전역 limiter 적용

**데이터**
- [ ] 일일 pg_dump + 오프사이트, 복구 리허설 1회 이상 성공(row count 대조)
- [ ] 주문/체결/이벤트 테이블 5년 보존 정책 및 CSV 추출 스크립트

**보안**
- [ ] gitleaks 히스토리 스캔 결과 0건, `.env` ACL 적용, BitLocker 활성
- [ ] GitHub Actions SHA 핀·permissions 최소화·실거래 시크릿 미보관
- [ ] 키 노출 플레이북 작성 및 키 재발급 절차 1회 연습

**변경 관리**
- [ ] live 배포는 태그 릴리스 + 장외 배포 창만 허용, 롤백 절차 연습 1회
- [ ] ADR 5건(실행 환경, 클라우드 IP 실측, 백업 정책, paper/live 분리, JNA 제거) 작성
- [ ] 소급 포스트모템 4건 완료, 액션 아이템 전부 종료

**세무·기록**
- [ ] 연간 체결·수수료·세금 추출 가능 확인, 세무사 1회 상담(대주주 요건·종합과세 해당 여부)

---

## 부록: 미확인 항목 목록

1. 키움 REST API 해외/클라우드 IP 차단 정책(공식 문서 미기재) → 실측 필요. 단 **허용 IP 등록 필수(10개)는 확인**
2. 키움 App Key 자체 만료: 2차 자료(알고랩)는 "만료일 없음, 분실 시 갱신" — 공식 문구 미확인. **3개월 미접속 자동 해지(실서버 기준)는 공식 확인**
3. 키움 모의서버 정기 점검 시간대 → openapi.kiwoom.com "시스템작업알림"
4. ~~Docker Desktop 로그인 없이 기동~~ → 공식 미지원 확인(로드맵 #515 open)
5. AWS Lightsail 서울 리전 지원 여부(번들 페이지 미기재, 존재하는 것으로 알려짐)
6. Oracle Free Tier 유휴 인스턴스 회수 조건(InfoQ 기사 미기재)
7. ~~Healthchecks.io Telegram 통합~~ → 공식 제공 확인
8. Vultr 서울 가격, 홈 NAS 모델별 Docker/JVM 실행 가능성
9. 7.1 비교표의 각 프레임워크 기능(일반 지식 기반, 각 공식 문서 재확인 권장)
10. 세무: ~~거래세율·금투세·대주주 기준~~ → 확인. 고빈도 개인매매의 소득 구분만 세무사 확인 필요
11. WSL2 절전 복귀 후 시계 드리프트가 실제 "스케줄 폭주" 원인인지(가설)
12. 분봉 용량 추정치(행당 바이트, 종목 수 가정)

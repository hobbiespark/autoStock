# autoStock 결정 로그

autoStock에 걸린 결정을 찾는 곳이다(공통 표준 문서 구조, VALUNIVO 4-2 S2). 기존 결정은 원래 문서에 두고 여기서 연결한다. autoStock 저장소의 새 결정은 3장에 `D-` 번호를 이어서 쓴다(지금까지 D-01~D-22).

## 1. 설계 결정 (ADR, `PLAN.md` 0절)

[PLAN.md](../PLAN.md) 0절의 ADR-1~ADR-14다. 논리적 MSA·물리적 모놀리스(ADR-1), PostgreSQL 이벤트 스토어(ADR-2), 매매 코어 검증 우선(ADR-3), 유지 사항(ADR-4), 엔지니어링 고도화(ADR-5), 아키텍처 원칙(ADR-6, 상세 [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md)), 단기 익절·손절 규칙(ADR-7), 인버스 ETF 숏(ADR-8), 공모주 반자동(ADR-9), FE React+TypeScript(ADR-10), 보유기간 지평별 전략(ADR-11), 게이트 v2(ADR-12), 검증 인프라 확장(ADR-13), DART 재무·공시 확장(ADR-14).

## 2. 사용자 결정 (D-01~D-21)

[upgrade-2026-10/12-decisions.md](upgrade-2026-10/12-decisions.md)에 선택지·권고·기한·결정 기록이 있다. 데이터 플랫폼 결정 D-15~D-19의 근거는 [data-platform-postgres.md](data-platform-postgres.md)다.

## 3. autoStock 저장소 결정 (D-22~)

| 번호 | 날짜 | 결정 | 이유 | 근거 |
|---|---|---|---|---|
| D-22 | 2026-11-01 | 표준 문서 구조는 기존 문서를 **옮기지 않고 색인**한다. `aiDoc/` 평면 문서·`PLAN.md`·`PROGRESS.md`는 제자리에 두고 계획목록·진행목록·이 로그·[상세 README](상세/README.md)에서 연결한다 | 코드 주석·스크립트·다른 브랜치가 경로로 가리켜 옮기면 링크가 깨지고 되돌리기 어렵다. 이동이 필요하면 링크 검사와 함께 별도 단계로 한다 | VALUNIVO 501 4장 권장안(D88로 진행, 데브가 바꿀 수 있음) |
| D-23 | 2026-11-05 | 공모주 청약일 재판단 텔레그램 명령 `/ipo <회사> <경쟁률> [청약건수] [일반물량]`을 둔다. **추천 등급은 바꾸지 않고** 균등·비례 예상 배정과 청약 금액만 다시 계산한다(비례 추가분 기대 배정 1주 미만이면 균등 최소 수량만 권장) | 사용자 요청("청약일에 경쟁률 등을 봇으로 알려주면 재판단 후 추천"). 일반청약 경쟁률로 등급을 바꾸는 규칙은 검증된 적이 없어(추천은 청약 전 값만 쓰도록 사전 선언) 새 trial 없이 넣을 수 없다. 배정 계산은 산술이라 바로 쓸 수 있다 | [ipo-automation.md](ipo-automation.md) 7절 |
| D-25 | 2026-11-06 | 전략·예산 변경 때 성과 측정 시작일을 옮겨 지난 기록을 자르지 않고, `monitor.performance.periods`에 변경 구간을 더해 **구간마다 운용 자금 대비·거래일당·거래당 수익률로 비교**한다. 성과 추이 화면은 첫 구간부터 보인다. 일일 리포트 누적(`since`)은 그대로 | 사용자 요청("전략 변경 시 전후 수익률 비교 가능한 성과"). 운용 자금이 4.86억→2,000만→500만→200만 원으로 바뀌어 금액 비교가 안 된다. 번호는 미병합 `claude/as-ipo-live-sell`의 D-24와 겹치지 않게 D-25 | [strategy-change-performance.md](strategy-change-performance.md) |
| D-26 | 2026-11-06 | 공모주 화면은 **회사마다 한 줄**(대표 신고서·가장 진행된 상태·빈 칸은 다른 신고서 값)로 보이고, 알림은 **추천(비례까지·균등만·스팩) 딜만** 보낸다(신규·수요예측 결과·주간 요약에서 판단 전·패스 제외). DB의 신고서별 이력은 그대로 | 사용자 요청(옵토닉스 예정·진행 중복, "청약 추천되지 않는 정보는 알림 필요 없다", 기 입력 화면 표시). 번호는 미병합 `claude/as-ipo-live-sell` D-24·develop D-25 다음 | [ipo-automation.md](ipo-automation.md) 9절 |

## 4. autoStock에 걸리는 VALUNIVO 결정

원문은 `C:\ai\VALUNIVO\aiDoc\01-decision-log.md`다. 여기에는 무엇을 정했는지만 한 줄로 적는다.

| VALUNIVO 결정 | autoStock에 미치는 것 |
|---|---|
| D96 | 앱 알림은 자체 발송 + 플랫폼 사본 |
| D107 | 공통 표준 하나에 단계적으로 수렴, 바깥 면 먼저(4-2) |
| D132 | 원본 변경은 장 마감 후(평일 16:00 이후)·주말, 별도 브랜치, main 병합·운영 반영은 데브에게 다시 묻기 |
| D154 | git flow: `develop` 개발 통합, `main` 운영 |
| D182 | 화면 토큰은 CSS 변수(`valunivo-tokens.css` 복사본), 부품은 직접 CSS |
| D213 | id·pw·키의 원본은 Git 밖 env-input, 앱 설정은 생성기로 만든다 |
| D239 | 운영도 모의 서버로 먼저 운영, 실전 키는 데브 지시 뒤 |
| D256 | `application.yml`에 DB 계정 기본값을 두지 않는다 |
| D259 | 외부 키는 개발·운영이 같은 값을 쓴다 |
| D336 | 품질 관문 `scripts/quality_gate.py`·커밋 전 훅은 VALUNIVO와 같은 출력, DB 시험은 필수(`--no-db`만 일부러 뺌), 지금 규칙 유지 |
| D339 | 예상 평문(`examples`)은 플랫폼과의 검색·분류·중계용 기계 정보라 `/autostock_help`·화면 명령 목록에 보이지 않는다(설명서 `/api/app/v1/manifest`에만 둔다, VALUNIVO 812) |
| D-27 | 2026-11-11 | 공모주 청약 추천에 **A 보유 계좌** 항목을 더한다 — 인수인(대표·공동·인수) 중 계좌를 가진 증권사가 있으면 +1, 등급도 이 합으로 정한다. 계좌가 없는 증권사는 보유 계좌로 못 하는 공모를 많이 맡은 곳부터 **한 달에 한 곳**만 텔레그램으로 개설을 요청한다. 보유 목록은 `ipo.subscription.held-brokers` | 사용자 요청("계좌 개설 제한이 있는 만큼 보유 증권사에서 청약 가능하면 +점수, 보유하지 않은 계좌 증권사는 1개월에 1개씩 개설 요청"). 수익률 예측이 아닌 실행 가능성 항목이라 T-IPO-1 사전 선언 뒤 새 trial T-IPO-2로 적는다. 번호는 미병합 `claude/as-ipo-live-sell` D-24·develop D-26 다음 | [ipo-subscription-strategy.md](ipo-subscription-strategy.md) 6절 |

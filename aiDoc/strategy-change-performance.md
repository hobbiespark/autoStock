# 전략 변경 전후 성과 비교 (2026-11-06, D-25)

- 요청(사용자 11/6): "전략 변경 시 전후 수익률 비교 가능한 성과 보이게 수정 필요".
- 지금까지는 전략·예산을 바꿀 때마다 성과 측정 시작일(`monitor.performance.since`)을 옮겨 **이전 기록을 화면에서 잘라 냈다**(10/22 → 10/23). 그래서 바꾸기 전과 후를 나란히 볼 수 없었다. 또 운용 자금이 4.86억 → 2,000만 → 500만 → 200만 원으로 바뀌어 손익 금액을 그대로 비교할 수 없다.

## 1. 바뀐 것

| 항목 | 내용 |
|---|---|
| 변경 이력 | `monitor.performance.periods` — 변경 시점마다 한 줄(시작일·변경 내용·그 구간 운용 자금). 구간 i = since_i ~ 다음 구간 전날, 마지막 구간은 오늘까지. 아직 오지 않은 구간은 뺀다 |
| 구간 비교 API | `GET /api/performance/periods` — 구간마다 전체·전략별 지표(아래 2절) |
| 성과 추이 화면 | 맨 위 **전략 변경 전후 비교** 표(+ 를 누르면 전략별), 일별 차트에 변경 시점 세로선, **운용 자금 대비 일별 수익률** 차트 추가. 추이는 첫 구간부터 보인다(예전엔 `since`부터) |
| 그대로인 것 | 일일 리포트 누적은 계속 `since`(10/23)부터. 지난 기록은 지우지 않는다. 마이그레이션 없음 |

## 2. 지표(실현손익만 — 보유 중 평가손익 제외)

| 지표 | 식 | 쓰임 |
|---|---|---|
| 실현손익 | 구간 매도 실현손익 합(추정 비용 차감, `strategy_fills`) | 금액 |
| 운용 자금 대비 | 실현손익 ÷ 그 구간 운용 자금 | 운용 자금이 달라도 비교 |
| 거래일당 | 운용 자금 대비 ÷ 거래일 수(`daily_performance` 행 수) | 구간 길이가 달라도 비교 |
| 청산·승률 | 종목·매도일마다 청산 1건(부분 체결 합침, 일일 리포트와 같은 기준), 이긴 청산 ÷ 청산 | 빈도·적중 |
| 거래당 수익률 | 실현손익 ÷ 매도 원가(매도 전 평단 × 수량) | 금액 규모와 무관한 전략 자체의 질 |

- 평단을 모르는 매도는 뺀다. 운용 자금이 0이거나 청산이 없으면 해당 비율은 비운다("-").

## 3. 등록한 구간(시작일 = 실제 반영·재기동 다음 날)

| 시작일 | 변경 내용 | 운용 자금 | 근거 |
|---|---|---|---|
| 10/16 | 다전략 모의 시작(계좌 전체 기준) | 약 4.86억 | [multi-strategy-paper-2026-10-16.md](multi-strategy-paper-2026-10-16.md) 1절. 10/22 운용 자금 전 보유 정리일을 포함한다 |
| 10/23 | 운용 자금 2,000만 원 | 2,000만 | 같은 문서 8절(10/21 결정, 10/22 정리 뒤 첫 표본 10/23) |
| 10/27 | 운용 자금 500만 원 | 500만 | 같은 문서 8절(10/26 21:31 재기동) |
| 10/31 | 200만 원·단타 3분할·스윙 이월·최소 유의미 수량 | 200만 | [split-buy-swing-carry-2026-10-30.md](split-buy-swing-carry-2026-10-30.md) 8절(10/30 17:28 재기동) |

- 10/31 이후 전략·위험 한도 변경 커밋은 없다(판 올림·플랫폼 연동뿐, 2026-11-06 `git log` 확인). 11/6 모의/실전 전환(D301)은 실전이 별도 DB라 구간이 아니다.

## 4. 앞으로 전략·예산을 바꿀 때

1. `application.yml`의 `monitor.performance.periods`에 한 줄을 더한다 — `since`는 실제 반영(재기동) 다음 날, `label`은 바꾼 내용, `capital-krw`는 그때 운용 자금.
2. `since`(일일 리포트 누적 시작일)는 바꾸지 않아도 된다. 리포트 누적도 새로 세고 싶을 때만 옮긴다.
3. 결과를 본 뒤 규칙을 바꾸면 새 trial로 기록한다(PROGRESS 3절 원칙) — 구간 표는 그 비교 근거가 된다.

## 5. 구현

| 모듈 | 파일 | 내용 |
|---|---|---|
| portfolio | `StrategyBook.periodStats`·`PeriodStat` | 구간 전략별 청산·승·실현손익·매도 원가 |
| monitor | `PerformanceProperties`(`periods`, `trendClamp`), `PerformancePeriods`(새), `PerformanceController`(`/periods`, 추이 하한), `DailyPerformanceRepository.countByTradeDateBetween`, `view.PerformancePeriodView`(새) | 구간 비교 계산·API |
| 화면 | `frontend/src/pages/PerformancePage.tsx`·`api.ts`·`types.ts`, 빌드 결과 `app/src/main/resources/static` | 표·세로선·일별 수익률 차트 |
| 설정 | `application.yml` | 구간 4개 |

## 6. 검증(2026-11-06, 작업트리 `C:/ai/_wt/autoStock-perf-compare`, JDK 25·Node 24)

- 새 시험: `PerformancePeriodsTest` 5(구간 경계·지금 구간·비율·비움·실제 `application.yml` 바인딩 4구간), `StrategyBookPeriodStatsTest` 2(10/23 실측 모양 — 분할 매도 청산 1건·원가), `PerformanceControllerTest` 1 추가(첫 구간부터 추이·빈 구간 목록).
- 전체 `./gradlew test --rerun --continue`: 1,319건(바인딩 시험 추가 전), 실패 0, 건너뜀 23(실데이터 실험·외부 스모크·상호 운용·Windows 전원 — 원래 조건부). `ArchitectureRulesTest`·`ModularityTests` 통과.
- 화면: `npm ci` → `npm run build`(tsc 타입 검사 포함) 성공. 번들 500kB 경고는 기존과 같다.
- **미확인:** 운영 DB 실제 숫자로 본 표 — 작업 중 운영 DB 읽기가 자동 모드 안전 검사에서 거부돼 반영 뒤 화면으로 확인한다. 화면 모양도 반영 뒤 확인.

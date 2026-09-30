# 03. FE 관점 조사 — 프레임워크·툴체인·품질 (2026-10-01)

> 문서 묶음: [00-README](00-README.md) · [01 현황](01-current-state.md) · 조사 [02 BE](02-research-be.md) · [03 FE](03-research-fe.md) · [04 디자인](04-research-design.md) · [05 기획](05-research-planning.md) · [06 인프라](06-research-infra.md) · [07 주식거래①시장·제도](07-research-trading-market.md) · [08 주식거래②전략·LLM](08-research-trading-strategy.md) · 감사 [09 BE](09-audit-be.md) · [10 FE](10-audit-fe.md) · **[11 실행 계획](11-execution-plan.md)** · [12 결정 목록](12-decisions.md)


- 대상: Vite 5.4 + React 18.3 + TS 5.6 + TanStack Query 5.59 + recharts 2.13, 탭 4개+, `/api/dashboard` 2초 폴링, 낙관적 업데이트 금지, Backend = Source of Truth, 전역 상태/라우터 미도입, 720px 반응형, Spring Boot static 서빙(gzip 61KB).
- 버전·날짜는 **npm 레지스트리(`npm view … time`, 2026-10-01 조회)** 와 공식 블로그/문서로 확인. 확인 못 한 항목은 **[미확인]** 표기.
- 조사 중 웹 페이지 본문 조회 도구가 세션 한도로 중단되어(4·5·7절 일부), 해당 절은 검색 결과 제목/URL과 기존 지식 기반이며 [미확인] 표기를 유지함.
- 난이도: 하(반나절) / 중(1~3일) / 상(1주+).

---

## 보강 확인·정정 (2026-10-01, npm 레지스트리 직접 조회)

| 항목 | 결과 | 근거 |
|---|---|---|
| typescript-eslint 8.71.0 | 피어 `eslint ^8.57 \|\| ^9 \|\| ^10`, **`typescript >=4.8.4 <6.1.0`** → TS 7 불가 | `npm view typescript-eslint peerDependencies`, [dependency-versions](https://typescript-eslint.io/users/dependency-versions/) |
| eslint-plugin-jsx-a11y 6.10.2 | 피어 **`eslint ^3 … ^9`** → ESLint 10 불가. 포크 `eslint-plugin-jsx-a11y-x@0.2.0`(피어 ^9/^10) 존재 | npm view |
| eslint-plugin-react-hooks 7.1.1 | 피어 ESLint ≤10 | npm view |
| vitest 5.0.3 | 피어 `vite ^6.4 \|\| ^7 \|\| ^8`, **엔진 Node ^22.12 / ^24 / >=26** | npm view |
| @vitejs/plugin-react 6.1.1 | 피어 **`vite ^8.0.0`**, 엔진 Node ^20.19 / >=22.12 | npm view |
| 작업 환경 Node | v22.22.2(FE 빌드는 AI 작업 환경에서 `npm ci` 후 빌드 — aiDoc/README 인계 메모) → 위 요구 충족. CI에는 setup-node 22 필요 | `node -v` |
| **확정 조합** | Vite 8.3 + plugin-react 6.1 + **TS 6.0.x** + **ESLint 9 flat** + typescript-eslint 8.71 + jsx-a11y 6.10 + react-hooks 7.1 + Vitest 5 + RTL 16 + Playwright 1.63 | 위 |

이 문서는 FE 기술 관점만 담는다. 디자인 시스템·접근성·사용자 여정·와이어프레임은 [04 디자인](04-research-design.md)으로 분리했다.

## 0. 요약 결론

| 결정 | 권고 | 근거 |
|---|---|---|
| React | **19.3 업그레이드 권고(중)** — 코드모드 + 타입 수정 위주. Compiler는 선택. | 19.0(2024-12) 이후 안정, 19.3(2026-09-09)에서 View Transitions·Fragment ref 안정화 |
| Vite | **8.x 업그레이드 권고(중)** — Node 20.19+/22.12+. rolldown-vite 경유 불필요(소규모). | 8.0(2026-03-12), 최신 8.3.1 |
| TypeScript | **6.0.x로 확정(하)** — 7.0은 typescript-eslint 지원 후(보강 확인: typescript-eslint 8.71 피어 `typescript >=4.8.4 <6.1.0`) | 7.0은 Go 네이티브, 6.0 설정 기본값 변경 |
| TanStack Query | 5.104 유지 업그레이드(하). 폴링 유지. `refetchIntervalInBackground:false` 명시. | |
| 라우터 | **미도입 유지**. 탭 5개는 `URL hash + useState`로 충분. | React Router 8은 ESM-only·Node 22.22+·React 19.2+ 요구 |
| 차트 | **recharts 3.10 업그레이드(중)** — accessibilityLayer 기본 on. 캔들/틱 차트 필요 시 lightweight-charts 5.2 병행. | |
| 실시간 | **2초 폴링 유지**, 탭 비활성 시 정지. SSE는 이벤트 피드 한정 2단계 옵션. | |
| UI | 라이브러리 없이 CSS만 유지 + 디자인 토큰(DTCG 2025.10 형식) + `light-dark()`. 위험 동작은 타이핑 확인·쿨다운. | |
| 접근성 | KWCAG 2.2(33항목)·WCAG 2.2 AA 기준. 라이브 리전(`aria-live="polite"`) + 표 우선. | |
| 품질 | Vitest 5 + RTL 16 + Playwright 1.63 스모크 + **ESLint 9 flat**(보강 확인: jsx-a11y 6.10.2 피어가 ESLint ≤9) + typescript-eslint 8 + Prettier 3. | |
| 모바일 | PWA(설치형) + Tailscale Serve. 웹 푸시 대신 텔레그램 알림 유지. | |

---

## 1. 프레임워크·툴체인 현황

### 1.1 React 19.x

| 항목 | 사실 | 출처 |
|---|---|---|
| 최신 안정 | **19.3.0 (2026-09-09)**. 19.0.0 2024-12-05, 19.1.0 2025-03-28, 19.2.0 2025-10-01 | npm; https://react.dev/blog/2026/09/09/react-19-3 |
| 19.0 핵심 | Actions(`useActionState`, `useFormStatus`, `useOptimistic`), `use()`, **ref를 일반 prop으로**(forwardRef 불필요), `<Context>`를 Provider로, 문서 메타데이터, 리소스 프리로드 API | https://react.dev/blog/2024/12/05/react-19 |
| 19.2 | **`<Activity mode="visible\|hidden">`** (hidden 시 effect 해제·업데이트 지연), `useEffectEvent`, `cacheSignal`(RSC), Chrome DevTools Performance Tracks, Partial Pre-rendering, `useId` 접두사 `_r_` | https://react.dev/blog/2025/10/01/react-19-2 |
| 19.3 | **`<ViewTransition>` 안정화**(enter/exit/update/share, `addTransitionType`), **Fragment ref 안정화**, `browser()`(SSR 옵트아웃), Trusted Types, Server Components에서 Context 직접 렌더. 파괴적 변경 없음 | https://react.dev/blog/2026/09/09/react-19-3 |
| React Compiler | **1.0 안정(2025-10-07)**, `babel-plugin-react-compiler@1.0.0`. React 17/18/19 지원(17/18은 `react-compiler-runtime`). Vite는 `@vitejs/plugin-react` babel 옵션으로 활성화. 성능: 초기 로드 최대 12%, 일부 인터랙션 2.5배(Meta Quest Store) | https://react.dev/blog/2025/10/07/react-compiler-1 |
| Compiler 네이티브 | `@vitejs/plugin-react@6.1.0`(2026-08-20) "experimental native React Compiler": `oxc-transform-react` 설치 후 `compiler: true`. 6.1.1 최신(2026-08-28) | https://github.com/vitejs/vite-plugin-react/releases/tag/plugin-react@6.1.0 |
| Lint | `eslint-plugin-react-hooks@7.1.1`(2026-04-17). v6+에서 flat config 기본, Compiler 기반 규칙(`set-state-in-render`, `set-state-in-effect`, `refs`) | React 19.2 블로그 |

**18→19 마이그레이션 함정** (https://react.dev/blog/2024/04/25/react-19-upgrade-guide)
- 제거: `propTypes`/함수 컴포넌트 `defaultProps`, 레거시 Context, string ref, `ReactDOM.render/hydrate/findDOMNode`, `react-dom/test-utils`의 `act`(→ `react`에서 import), UMD 빌드.
- TS: `useRef()` 인자 필수, `ReactElement['props']`가 `any`→`unknown`, ref 콜백은 블록문으로(암묵 반환 금지), 전역 `JSX` 네임스페이스 제거(`React.JSX`), `useReducer` 제네릭 변경. → `npx types-react-codemod@latest preset-19 ./src`.
- 에러 처리: 렌더 에러 재던지기 안 함 → `createRoot(el, { onUncaughtError, onCaughtError })`.
- StrictMode에서 dev 이중 렌더 시 `useMemo`/`useCallback` 결과 재사용 등 동작 차이.
- 서드파티: recharts 2.13은 React 19 peerDep 미선언 가능 → **recharts 3.x와 동시에 올리는 것이 안전**.

**이 시스템 함의·권고**
- 코드베이스가 작고(탭 5개, 전역 상태 없음) 클래스 컴포넌트 없음 → 코드모드 후 타입 수정만으로 1일 내 완료 가능. **난이도 중**.
- `<Activity>`: 비활성 탭을 언마운트 대신 `hidden`으로 두면 스크롤·필터 상태를 보존하면서 effect(폴링 구독)는 해제됨 → 라우터 없는 탭 구조와 궁합 좋음. 단 TanStack Query의 `useQuery` 구독은 컴포넌트 언마운트 없이 유지될 수 있으므로 `subscribed: false` 또는 `enabled`로 비활성 탭 폴링 차단을 병행.
- `<ViewTransition>`: 탭 전환 페이드 정도만. 금융 대시보드에서 숫자 갱신에 애니메이션은 **비권고**(인지 부하·`prefers-reduced-motion`).
- React Compiler: 메모이제이션 실수 방지 가치는 있으나 2초 폴링으로 전체 트리가 어차피 리렌더되는 구조라 이득 제한적. **선택(난이도 하, 기존 결정 충돌 없음)**. 도입 시 `eslint-plugin-react-hooks@7` 규칙부터 적용해 위반 0 확인 후 켠다.

### 1.2 Vite 7/8

| 항목 | 사실 | 출처 |
|---|---|---|
| 최신 | **8.3.1 (2026-09-24)**. 7.0.0 2025-06-24, 8.0.0 2026-03-12 | npm; https://vite.dev/blog/announcing-vite8 |
| Rolldown | Vite 8에서 esbuild+Rollup → **Rolldown(Rust) + Oxc** 단일 툴체인. 빌드 10~30배 향상 주장. 플러그인 API는 Rollup 호환 | 동상 |
| Node 요구 | **20.19+ / 22.12+** (ESM-only 배포, Vite 7과 동일) | 동상 |
| 기본 브라우저 타깃 | Chrome 111 / Edge 111 / Firefox 114 / Safari 16.4 ("Baseline Widely Available", 2026-01 기준) | https://vite.dev/guide/migration |
| 옵션 변경 | `esbuild.*`→`oxc.*`, `build.rollupOptions`→`build.rolldownOptions`, `optimizeDeps.esbuildOptions`→`rolldownOptions`(호환 레이어 자동 변환). object 형 `manualChunks` 제거, AMD/System 출력 제거, CJS default import 동작 통일 | 동상 |
| 미니파이 | JS: Oxc Minifier, CSS: Lightning CSS 기본 | 동상 |
| plugin-react | **6.1.1 (2026-08-28)**, 6.0.0 2026-03-12(Vite 8 동시). 5.0.0 2025-08-07 | npm |
| rolldown-vite | 7.3.1 (2026-01-09) — Vite 7에서 Rolldown 미리 쓰는 패키지, Vite 8 이후 불필요 | npm |

**함의·권고**: 5.4→8.x는 **설정 몇 줄 수준(중)**. 주의: (1) Node 버전(현재 서버/CI Node 확인), (2) `manualChunks` 객체형 사용 여부, (3) 브라우저 타깃 상향으로 `light-dark()` 등 최신 CSS를 트랜스파일 없이 사용 가능, (4) Lightning CSS가 CSS 미니파이 기본 → 커스텀 프로퍼티·중첩 문법 정상 처리. gzip 61KB 산출물은 Rolldown 청크 전략 변화로 ±수 KB 변동 가능 → 빌드 후 `rollup-plugin-visualizer@7.1.1`로 비교.

### 1.3 TypeScript 5.9 / 6.0 / 7.0

| 항목 | 사실 | 출처 |
|---|---|---|
| 5.9 | 5.9.3 (2025-09-30) — 5.x 마지막 | npm |
| 6.0 | **6.0.2 (2026-03-23) 첫 안정, 6.0.3 (2026-04-16) 최신**. "JS 코드베이스 기반 마지막 릴리스". 기본값 변경: `strict:true`, `module:esnext`, `target:es2025`, `types:[]`, `rootDir:.`, `noUncheckedSideEffectImports:true`. 6.0 경고→7.0 오류: `target es5`, `baseUrl`, `moduleResolution node/classic`, `esModuleInterop:false`, `module amd/umd/systemjs`, `outFile`, `asserts` import 구문 | https://devblogs.microsoft.com/typescript/announcing-typescript-6-0/ |
| 7.0 | **7.0.2 (2026-07-08)** — Go 네이티브 컴파일러(`tsgo`), `npm i -D typescript`로 `tsc` 대체. 6.0과 동일한 타입 검사/CLI 동작. **프로그램 API 없음(7.1 예정)**, `@typescript/typescript6`로 `tsc6` 병행 가능. 빌드 8~12배, 메모리 -18% | https://devblogs.microsoft.com/typescript/announcing-typescript-7-0/ ; https://www.infoq.com/news/2026/08/typescript-7-released/ |
| VS Code | 전용 TS 7 확장. Vue/Angular/Svelte 임베디드 툴은 6.0 필요 | 동상 |

**함의·권고**: 5.6→**6.0.3 먼저**(tsconfig에서 `baseUrl` 제거 → `paths`만, `types:["vite/client"]` 명시, `moduleResolution:"bundler"` 확인) → 7.0.2. Vite는 esbuild/oxc로 트랜스파일하므로 `tsc --noEmit`만 7로 바꾸면 되고 빌드 파이프라인 영향 없음. **보강 확인**: typescript-eslint 8.71(2026-09-28)의 피어는 `typescript >=4.8.4 <6.1.0` — **TS 7 미지원**. 따라서 lint를 도입하는 이 계획에서는 **TS 6.0.x에 멈춘다**. **난이도 하**.

### 1.4 TanStack Query 5.x

| 항목 | 사실 | 출처 |
|---|---|---|
| 최신 | **5.104.0 (2026-09-26)**. 5.59.0은 2024-10-01 → 2년치 패치 누락 | npm |
| 기본값 | `staleTime:0`, `gcTime:5분`, `retry:3`(지수 백오프), `refetchOnWindowFocus/Reconnect/Mount:true`, 구조적 공유 | https://tanstack.com/query/latest/docs/framework/react/guides/important-defaults |
| 폴링 | `refetchInterval: number\|false\|(query)=>…`, **`refetchIntervalInBackground`(기본 false = 창 비포커스 시 폴링 정지)** | https://tanstack.com/query/latest/docs/framework/react/reference/useQuery |
| Suspense | `useSuspenseQuery`/`useSuspenseQueries`; `throwOnError`는 기본적으로 캐시 없을 때만; `QueryErrorResetBoundary`; Suspense 모드에서 `enabled`·`placeholderData` 사용 불가 | https://tanstack.com/query/latest/docs/framework/react/guides/suspense |
| 스트리밍 | `streamedQuery`(experimental): AsyncIterable→배열 누적, `refetchMode: 'reset'\|'append'\|'replace'` | https://tanstack.com/query/latest/docs/reference/streamedQuery |

**함의·권고**: 단일 `/api/dashboard` 폴링은 `useQuery` 1개 + `select`로 탭별 파생 + `notifyOnChangeProps`로 리렌더 제한이 최선. **Suspense 모드는 비권고** — 폴링 중 `placeholderData: keepPreviousData` 유지가 더 중요하고, 오류 시 마지막 정상 데이터 + 배너가 운영 대시보드 UX에 맞음. 낙관적 업데이트 금지 결정과 충돌 없음(뮤테이션 후 `invalidateQueries`만).

### 1.5 라우터: TanStack Router vs React Router

| 항목 | 사실 | 출처 |
|---|---|---|
| React Router | **8.4.0 (2026-09-15)**, 8.0.0 2026-06-17. ESM-only, **Node 22.22+**, React 19.2+, Vite 7+(InfoQ 보도 기준). 미들웨어 기본, `react-router-dom` deprecated → `react-router`/`react-router/dom`. 연 1회 메이저. v6·Remix v2 EOL | https://www.infoq.com/news/2026/08/react-route-v8/ ; npm |
| TanStack Router | 1.170.40 (2026-09-27). 타입 세이프 경로/검색 파라미터/스키마 검증, SWR 로더 캐시. 번들 크기 수치는 문서에 배지로만 제공 [미확인] | https://tanstack.com/router/latest/docs/framework/react/comparison |

**권고**: 탭 5개·인증 없음·서버 라우팅 없음 → **미도입 유지**. 대신 `location.hash`(`#orders`, `#audit?from=…`)를 단일 `useHashTab()` 훅으로 동기화하면 새로고침·북마크·FE-5 감사 탐색 필터 공유가 가능(난이도 하). 향후 화면이 10개 이상·중첩 레이아웃·검색 파라미터 스키마가 필요해지면 TanStack Router(타입 세이프 search params가 감사 탐색과 궁합).

---

## 2. 차트 라이브러리

| 라이브러리 | 최신(npm) | 렌더링 | 특징 | 접근성 | 이 시스템 적합도 |
|---|---|---|---|---|---|
| **recharts** | **3.10.1 (2026-07-25)**, 3.0.0 2025-06-23, 3.11 canary(테마 시스템) | SVG(React) | 3.0: 내부 상태 노출 제거(`CategoricalChartState` 삭제), `activeIndex`·`Legend payload` 등 prop 제거, z-index=JSX 순서, 다중 Y축 `yAxisId` 알파벳순, Tooltip `portal`/`axisId`, Y축 `width="auto"`, `symlog`, `recharts-scale`/`react-smooth` 의존 제거. React 16.8+/TS 5.x | **`accessibilityLayer` 기본 true**(키보드 탐색·ARIA) | **유지·3.x 업그레이드**. 성과 곡선·슬리피지 분포·일별 손익 막대 등 데이터 수백 점 규모에 충분 |
| lightweight-charts | **5.2.1 (2026-08-12)**, 5.0 2025-01-02 | Canvas | 5.0: 통합 `addSeries` API, 멀티 페인, 워터마크·마커는 플러그인, CJS 제거, 번들 -16%(약 35KB). 5.1: 데이터 컨플레이션(줌아웃 시 병합). 5.2: hit-testing(`hoveredItem`) | 5.2.1: **드롭인 Accessibility 플러그인**(키보드·ARIA·SR 안내) | 캔들/틱/체결 마커 시각화가 필요할 때만 병행. TradingView 로고 표기 의무(라이선스 확인) |
| Apache ECharts | **6.1.0 (2026-05-19)**, 6.0 2025-07-30 | Canvas/SVG | 6.0: 디자인 토큰 기반 새 테마, 시스템 다크모드 자동, 코드/비스웜 등 신규 차트 | `aria` 옵션(decal·라벨) | 기능 과다·번들 큼(트리셰이킹 필요). 비권고 |
| visx | **4.0.0 (2026-06-11)** | SVG(React, d3 저수준) | React 18/19, deep import 금지, PropTypes 제거, d3-shape v3. 커뮤니티에서 유지보수 속도 우려 제기 | 직접 구현 | 비권고(구현 비용) |
| uPlot | 1.6.32 (2025-03-14) | Canvas | ~50KB min, 166k점 25ms, 스트리밍 3,600점 60fps CPU 10%. 애니메이션·라벨 충돌 회피 없음. MIT | 직접 구현 | 틱 단위 실시간 라인이 필요해질 때 후보 |

출처: https://github.com/recharts/recharts/wiki/3.0-migration-guide ; https://github.com/recharts/recharts/releases ; https://tradingview.github.io/lightweight-charts/docs/release-notes ; https://echarts.apache.org/handbook/en/basics/release-note/v6-feature/ ; https://github.com/airbnb/visx/blob/master/MIGRATION.md ; https://github.com/airbnb/visx/discussions/1908 ; https://leeoniya.github.io/uPlot/

**차트 접근성 권고(KWCAG 2.2 6.1.1 대체 텍스트 / WCAG 1.1.1·1.4.1)**
1. 모든 차트 옆에 **동일 데이터의 `<table>`(접기 가능)** 또는 "표로 보기" 토글. 성과 탭은 표를 기본, 차트를 보조로.
2. `<figure role="img" aria-label="일별 손익, 최근 20영업일, 최대 +1.2%, 최소 -0.8%">` + `<figcaption>`에 핵심 수치 텍스트 요약.
3. 색만으로 상승/하락 구분 금지 → 마커 모양·패턴(`fill` 해칭)·라벨 병행.
4. `prefers-reduced-motion` 시 recharts `isAnimationActive={false}`.
5. recharts 3 `accessibilityLayer`는 기본 on이므로 커스텀 Tooltip이 키보드 포커스 시에도 렌더되는지 확인.

**난이도**: recharts 2.13→3.10 **중**(커스텀 Tooltip·Legend payload 사용처 재작성).

---

## 3. 실시간: 폴링 vs SSE vs WebSocket

| 방식 | 장점 | 단점 | 이 시스템 |
|---|---|---|---|
| **2초 폴링(현행)** | 단순, Backend=SoT 원칙과 정합, 재연결 로직 불필요, 스냅샷 일관성(한 응답 = 한 시점) | 지연 ≤2초, 유휴 시 낭비 | **유지**. `refetchIntervalInBackground:false`(기본) + `document.visibilityState` 체크로 비활성 탭 정지. 응답에 `serverTime`·`seq` 포함해 "N초 전 갱신" 표시 |
| SSE (`SseEmitter`) | 단방향 푸시, HTTP/1.1로 충분, 자동 재연결(`retry:`), 프록시 친화 | 서블릿 async timeout 관리(`spring.mvc.async.request-timeout`), 하트비트 필요, 브라우저당 HTTP/1.1 연결 6개 제한, 이벤트 유실 시 `Last-Event-ID` 처리 | **2단계 옵션**: 이벤트 피드·주문 상태 변경만 SSE로 "알림"하고, 데이터 자체는 폴링/`invalidateQueries`로 가져오는 **"SSE는 트리거, REST는 진실"** 패턴이 SoT 원칙과 가장 잘 맞음 |
| WebSocket | 양방향, 저지연 | Spring 설정·재연결·백프레셔 구현 부담, 개인 로컬 대시보드에 과함 | 비권고 |

**TanStack Query + SSE 결합 패턴**
- A) `EventSource` 수신 → `queryClient.invalidateQueries({queryKey:['dashboard']})` (가장 단순, SoT 유지).
- B) `queryClient.setQueryData`로 부분 갱신 — 낙관적 업데이트는 아니지만 서버 이벤트로만 쓰기. 스냅샷 일관성이 깨질 수 있어 이벤트 피드 전용.
- C) `streamedQuery`(experimental, `refetchMode:'append'`)로 이벤트 피드를 AsyncIterable로 누적 — API 미확정이라 **보류**.

**Page Visibility API**: `document.addEventListener('visibilitychange', …)`로 `hidden`이면 `refetchInterval:false` 반환(함수형), `visible` 복귀 시 즉시 `refetch()` + "복귀 후 갱신됨" 토스트. 킬스위치 상태는 복귀 직후 최우선 재조회.

출처: https://tanstack.com/query/latest/docs/framework/react/reference/useQuery ; https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/mvc/method/annotation/SseEmitter.html (Spring Framework 7.0.7 API 문서 존재 확인, 본문 세부 [미확인]) ; https://tanstack.com/query/latest/docs/reference/streamedQuery

**난이도**: 가시성 정지 **하**, SSE 트리거 **중**.

---

## 6. 품질 도구 — 소규모 최소 구성

| 도구 | 최신 | 요구사항·변경 | 권고 |
|---|---|---|---|
| Vitest | **5.0.3 (2026-09-30)**, 5.0.0 2026-09-03, 4.0 2025-10-22 | Vite ≥6.4, **Node ≥22.12**. 5: 브라우저 모드 Trace View, `vi.when`, 미await 비동기 단언 즉시 실패, 프로젝트가 루트 설정 상속. 3/4→5 마이그레이션 가이드 참조 | 도입(현재 테스트 없다면 5로 시작) |
| Testing Library | `@testing-library/react@16.3.3` (2026-08-27) | React 19 지원 | 훅·컴포넌트 단위 |
| Playwright | **1.63.0 (2026-09-04)** | | 스모크 3개: 대시보드 로드·킬스위치 다이얼로그(취소 경로만)·탭 전환 + axe |
| ESLint | **10.11.0 (2026-09-18)**, 10.0.0 2026-02-06 | **eslintrc 완전 제거, flat config만**, Node 20.19+/22.13+/24+, 설정 파일을 린트 대상 파일 디렉터리부터 탐색 | **9.x flat 권고**(jsx-a11y가 10 미지원 — 보강 확인) |
| typescript-eslint | 8.71.0 (2026-09-28) | TS 7 지원 [미확인] | 유지 |
| Prettier | 3.9.9 (2026-09-23) | | 유지 |
| Biome | 2.5.14 (2026-09-16), 2.5.0 2026-06-12 | 500+ 규칙, 크로스파일 CSS 클래스 검사, GritQL 플러그인 수정, a11y 규칙군 | ESLint+Prettier 대체 가능하나 `react-hooks` Compiler 규칙은 ESLint 전용 → **ESLint 유지** |
| 번들 분석 | `rollup-plugin-visualizer@7.1.1`(2026-08-14), `vite-bundle-analyzer@1.3.9` | Rolldown 호환 | 빌드 산출물 61KB 예산 회귀 감시 |
| Lighthouse CI | `@lhci/cli@0.15.1`(2025-06-25) | `lhci autorun` + `assert` 예산(performance≥0.9, a11y=1) | 선택(로컬 대시보드라 가치 낮음) |

출처: https://vitest.dev/blog/vitest-5.html ; https://eslint.org/blog/2026/02/eslint-v10.0.0-released/ ; https://biomejs.dev/blog/biome-v2-5/ ; https://googlechrome.github.io/lighthouse-ci/

**최소 구성 스크립트**: `lint`(eslint .), `typecheck`(tsc --noEmit), `test`(vitest run), `e2e`(playwright test --project=chromium), `analyze`(vite build + visualizer). CI 없이 `pre-push` 훅 하나로 충분.

---

## 7. 모바일·PWA·원격 접근

| 항목 | 사실 | 권고 |
|---|---|---|
| 설치형 PWA | `manifest.json`(`display: standalone`) + 서비스워커(캐시는 정적 자산만, API는 네트워크 우선). Spring static에 그대로 배치 | **도입(하)** — 홈 화면 아이콘·전체화면·상태바 색 |
| iOS 웹 푸시 | iOS/iPadOS **16.4+**, **홈 화면 추가된 웹앱만** 가능(Safari 탭 불가), 사용자 제스처로 구독, APNs 경유(개발자 프로그램 불필요), Badging API. 출처: https://webkit.org/blog/13878/web-push-for-web-apps-on-ios-and-ipados/ | 서버 VAPID 키·구독 저장·재구독 처리 부담 → **텔레그램 알림 유지**, 웹 푸시 비도입 |
| Declarative Web Push(iOS 18.4+) | 서비스워커 없이 JSON 푸시 [세부 미확인] | 추후 검토 |
| 127.0.0.1 바인딩 상태에서 모바일 접근 | **Tailscale Serve**: `tailscale serve 8080` → 테일넷 내 HTTPS(`https://host.tailnet.ts.net`), 인증 = 테일넷 로그인, 공개 노출 없음. Funnel은 공개 노출이라 비권고. **Cloudflare Tunnel + Access**: 공개 도메인 + Access 정책(이메일 OTP 등), 무료 티어 있음. 출처: https://tailscale.com/docs/features/tailscale-serve ; https://tailscale.com/compare/cloudflare-access (본문 [미확인]) | **Tailscale Serve 권고(하)** — 백엔드 바인딩 변경 불필요, 인증 자동. 킬스위치 등 쓰기 API는 추가로 앱 레벨 PIN/타이핑 확인 유지 |

---

## 9. 권고 요약 Top 10

| # | 권고 | 영향 | 난이도 | 기존 결정과 충돌 |
|---|---|---|---|---|
| 1 | Vite 5.4 → 8.3 (Node 20.19+/22.12+) | 빌드 속도·최신 CSS 타깃 | 중 | 없음(정적 서빙 유지) |
| 2 | React 18.3 → 19.3 + recharts 2.13 → 3.10 동시 업그레이드 | 접근성 레이어 기본 on, Activity로 탭 상태 보존 | 중 | 없음 |
| 3 | TS 5.9 → 6.0.x (7.0은 typescript-eslint 지원 후) | 설정 기본값 정비, 7.0 대비 | 하 | 없음 |
| 4 | 라우터 미도입 유지 + `useHashTab`으로 URL 상태 동기화 | FE-5 필터 공유·북마크 | 하 | 없음(경량 탭 결정 강화) |
| 5 | 2초 폴링 유지 + 가시성 기반 정지·복귀 즉시 재조회 | 자원 절약, 킬스위치 상태 신뢰 | 하 | 없음 |
| 6 | 위험 동작: 타이핑 확인·5초 쿨다운·dry-run 기본값·서버 응답으로만 갱신 | 오조작 방지 | 하 | 낙관적 업데이트 금지와 정합 |
| 7 | DTCG 2025.10 토큰 + `@layer` + `light-dark()`(폴백 포함) CSS-only 디자인 시스템 | 다크모드·일관성 | 하 | CSS-only 결정과 정합 |
| 8 | 라이브 리전 설계(이벤트 피드 `role="log"`, 긴급 assertive 1줄) + 차트 표 대체 | KWCAG 2.2/WCAG 2.2 AA | 중 | 없음 |
| 9 | Vitest 5 + RTL 16 + Playwright 1.63 스모크 3개 + axe + ESLint 9 flat(+jsx-a11y, react-hooks 7) | 회귀 방지 | 중 | 없음 |
| 10 | PWA 설치형 + Tailscale Serve로 모바일 확인, 알림은 텔레그램 유지 | 모바일 접근 | 하 | 루프백 바인딩 유지 가능 |

보류/비권고: SSE(2단계 옵션), React Compiler(선택), lightweight-charts(캔들 필요 시), 웹 푸시, ECharts/visx/MUI/Mantine, Suspense 쿼리 모드.

---

## 10. 버전 매트릭스 (npm `latest`, 2026-10-01 조회)

| 패키지 | 현재 | 최신 | 최신 배포일 | 주요 마일스톤 |
|---|---|---|---|---|
| react / react-dom | 18.3 | **19.3.0** | 2026-09-09 | 19.0 2024-12-05 · 19.2 2025-10-01 |
| vite | 5.4 | **8.3.1** | 2026-09-24 | 7.0 2025-06-24 · 8.0 2026-03-12 |
| @vitejs/plugin-react | ? | **6.1.1** | 2026-08-28 | 6.0 2026-03-12 · 6.1 네이티브 Compiler(experimental) |
| typescript | 5.6 | **7.0.2** | 2026-07-08 | 5.9.3 2025-09-30 · 6.0.2 2026-03-23 · 6.0.3 2026-04-16 |
| @tanstack/react-query | 5.59 | **5.104.0** | 2026-09-26 | 5.59 2024-10-01 |
| @tanstack/react-router | – | 1.170.40 | 2026-09-27 | |
| react-router | – | 8.4.0 | 2026-09-15 | 8.0 2026-06-17 (ESM-only, Node 22.22+) |
| recharts | 2.13 | **3.10.1** | 2026-07-25 | 3.0 2025-06-23 · 3.11 canary 테마 |
| lightweight-charts | – | 5.2.1 | 2026-08-12 | 5.0 2025-01-02 |
| echarts | – | 6.1.0 | 2026-05-19 | 6.0 2025-07-30 |
| uplot | – | 1.6.32 | 2025-03-14 | |
| @visx/visx | – | 4.0.0 | 2026-06-11 | |
| babel-plugin-react-compiler | – | 1.0.0 | 2025-10-07 | |
| eslint-plugin-react-hooks | ? | 7.1.1 | 2026-04-17 | |
| vitest / @vitest/browser | – | 5.0.3 | 2026-09-30 | 4.0 2025-10-22 · 5.0 2026-09-03 |
| @testing-library/react | – | 16.3.3 | 2026-08-27 | |
| @playwright/test | – | 1.63.0 | 2026-09-04 | |
| eslint | 9? | 10.11.0 | 2026-09-18 | 10.0 2026-02-06 |
| typescript-eslint | 8.x | 8.71.0 | 2026-09-28 | |
| prettier | 3.x | 3.9.9 | 2026-09-23 | |
| @biomejs/biome | – | 2.5.14 | 2026-09-16 | 2.5.0 2026-06-12 |
| axe-core / @axe-core/playwright | – | 4.13.0 | 2026-08-05 / 08-11 | |
| eslint-plugin-jsx-a11y | – | 6.10.2 | 2024-10-26 | 갱신 정체 |
| rollup-plugin-visualizer | – | 7.1.1 | 2026-08-14 | |
| @lhci/cli | – | 0.15.1 | 2025-06-25 | |
| Node.js 요구 | – | Vite 8: 20.19+/22.12+ · Vitest 5: 22.12+ · ESLint 10: 20.19+/22.13+/24+ · React Router 8: 22.22+ | | |

### 미확인 항목 목록
- ~~KWCAG 2.2 "2024 개정"~~ → 개정 근거 없음, 2.2(2022) 유지
- ~~typescript-eslint의 TS 7 지원~~ → 미지원 확인(`<6.1.0`)
- ~~jsx-a11y의 ESLint 10 호환~~ → 미지원 확인(피어 ≤9)
- TanStack Router/React Router 실측 번들 크기.
- `@starting-style` Baseline 정확 일자, `light-dark()` Widely available 일자(2026-11 예상, 커뮤니티 자료 기준).
- Spring `SseEmitter` 문서 세부(7.0.7 API 문서 존재만 확인), Tailscale/Cloudflare 비교 페이지 본문, Playwright 접근성 문서 본문.
- shadcn/ui·Radix·Ark UI·Mantine·MUI 최신 버전(npm 미조회).
- React Router 8의 React 19.2.7+ 요구 표기는 InfoQ 보도 기준.
- Declarative Web Push(iOS 18.4) 세부.

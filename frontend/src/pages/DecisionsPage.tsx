import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { fetchDecisions } from '../api';
import DecisionConclusionBadge from '../components/DecisionConclusionBadge';
import type { DecisionItem, Horizon } from '../types';

// 지평 탭 순서·라벨(ADR-11) — TEST는 아직 지평 프레임에 편입되지 않은 전략(테스트 시그널
// 등)의 RiskGate 거부 기록에 쓰인다. ALL은 서버 값이 아니라 이 화면의 "전체" 가상 탭이다.
const ALL_TAB = '전체' as const;
const HORIZON_ORDER: Horizon[] = ['DAY', 'SWING', 'MID', 'LONG', 'TEST'];
const HORIZON_LABELS: Record<string, string> = {
  DAY: '단타',
  SWING: '스윙',
  MID: '중기',
  LONG: '장기',
  TEST: '테스트',
};

function todayKst(): string {
  // 서버(GET /api/decisions)도 date 생략 시 KST 오늘로 조회하지만, 날짜 입력창(input
  // type=date)의 기본값은 FE가 직접 채워야 하므로 브라우저 로컬 시간대와 무관하게
  // Intl로 KST 날짜 문자열을 만든다.
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' }).format(new Date());
}

/**
 * 종목 선정 이유(지평별) 화면(FE-6, PLAN.md ADR-10 확장표) — "오늘 왜 이 종목을 사고/안
 * 샀나"를 지평(단타/스윙/중기/장기) 탭과 날짜로 조회한다. 대시보드와 달리 폴링하지 않고
 * (주문 이력·성과 화면과 동일 관례) 날짜가 바뀌면 쿼리 키가 바뀌어 자동 재조회된다.
 */
export default function DecisionsPage() {
  const [date, setDate] = useState<string>(todayKst());
  const [horizonTab, setHorizonTab] = useState<string>(ALL_TAB);

  const query = useQuery({
    queryKey: ['decisions', date],
    queryFn: () => fetchDecisions(date),
    refetchOnWindowFocus: false,
  });

  const items = query.data ?? [];

  // 데이터가 실제로 있는 지평만 탭으로 노출한다(요구사항: "데이터 있는 지평만 탭 활성화").
  const availableHorizons = useMemo(() => {
    const present = new Set(items.map((d) => d.horizon));
    return HORIZON_ORDER.filter((h) => present.has(h));
  }, [items]);

  const visibleItems = useMemo(
    () => (horizonTab === ALL_TAB ? items : items.filter((d) => d.horizon === horizonTab)),
    [items, horizonTab],
  );

  // 선택된 지평 탭에 더 이상 데이터가 없으면(날짜를 바꿔 그 지평이 사라진 경우) 전체로 되돌린다.
  useEffect(() => {
    if (horizonTab !== ALL_TAB && !availableHorizons.includes(horizonTab) && !query.isLoading) {
      setHorizonTab(ALL_TAB);
    }
  }, [availableHorizons, horizonTab, query.isLoading]);

  return (
    <div className="card" style={{ maxWidth: 1100 }}>
      <div className="page-header-row">
        <h2>종목 선정 이유</h2>
        <div className="row" style={{ marginBottom: 0 }}>
          <input type="date" value={date} onChange={(e) => setDate(e.target.value)} />
          <button className="secondary" onClick={() => query.refetch()} disabled={query.isFetching}>
            {query.isFetching ? '새로고침 중...' : '새로고침'}
          </button>
        </div>
      </div>

      <nav className="horizon-sub-nav">
        <button
          className={`horizon-sub-nav-btn ${horizonTab === ALL_TAB ? 'active' : ''}`}
          onClick={() => setHorizonTab(ALL_TAB)}
        >
          전체
        </button>
        {HORIZON_ORDER.map((h) => (
          <button
            key={h}
            className={`horizon-sub-nav-btn ${horizonTab === h ? 'active' : ''}`}
            disabled={!availableHorizons.includes(h)}
            onClick={() => setHorizonTab(h)}
          >
            {HORIZON_LABELS[h] ?? h}
          </button>
        ))}
      </nav>

      {query.isError && (
        <div className="error-banner">
          판단 근거 조회 실패 — {query.error instanceof Error ? query.error.message : String(query.error)}
        </div>
      )}

      {query.isLoading ? (
        <p className="muted">조회 중...</p>
      ) : visibleItems.length === 0 ? (
        <p className="muted">해당 날짜 판단 기록 없음 (전략 활성 시 09:05 기록됩니다)</p>
      ) : (
        <div className="decision-list">
          {visibleItems.map((item, idx) => (
            <DecisionRow key={`${item.symbol}-${item.strategyId}-${item.decidedAt}-${idx}`} item={item} />
          ))}
        </div>
      )}
    </div>
  );
}

function DecisionRow({ item }: { item: DecisionItem }) {
  const metricsEntries = Object.entries(item.metrics ?? {});
  return (
    <div className="decision-row">
      <div className="decision-row-head">
        <span className="decision-row-symbol">{item.symbol}</span>
        <DecisionConclusionBadge conclusion={item.conclusion} />
        <span className="muted">{HORIZON_LABELS[item.horizon] ?? item.horizon}</span>
        <span className="muted">{item.strategyId}</span>
        <span className="muted" style={{ marginLeft: 'auto' }}>
          {new Date(item.decidedAt).toLocaleString()}
        </span>
      </div>
      <p className="decision-row-reason">{item.reason}</p>
      {metricsEntries.length > 0 && (
        <table className="decision-metrics-table">
          <tbody>
            {metricsEntries.map(([k, v]) => (
              <tr key={k}>
                <td className="mono">{k}</td>
                <td>{v}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

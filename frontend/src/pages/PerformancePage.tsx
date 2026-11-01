import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Bar,
  CartesianGrid,
  ComposedChart,
  Legend,
  Line,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { fetchDailyPerformance } from '../api';

const DAY_OPTIONS = [30, 90] as const;

// 백테스트 CostModel의 slippagePct 가정치(SlippageCard와 동일 상수, 5bps).
const BACKTEST_ASSUMED_AVG_BPS = 5;

// 차트 색상 — 공통 토큰(valunivo-tokens.css)을 CSS 변수로 넘긴다(VALUNIVO 4-2 S6). SVG 표현 속성의
// var()는 현재 브라우저가 읽으므로 밝게·어둡게 테마를 따라 바뀐다. 막대·선은 파랑/초록으로 구분을 유지한다.
const COLORS = {
  bar: 'var(--vn-status-running-fg)',
  cumulative: 'var(--vn-status-done-fg)',
  avgSlip: 'var(--vn-status-running-fg)',
  maxSlip: 'var(--vn-color-danger)',
  grid: 'var(--vn-color-border)',
  text: 'var(--vn-color-text-muted)',
};

const TOOLTIP_STYLE = {
  background: 'var(--vn-color-surface)',
  border: '1px solid var(--vn-color-border)',
  color: 'var(--vn-color-text)',
};

interface ChartRow {
  tradeDate: string;
  realizedPnl: number;
  cumulativePnl: number;
  avgSlippageBps: number;
  maxSlippageBps: number;
}

export default function PerformancePage() {
  const [days, setDays] = useState<number>(90);

  const query = useQuery({
    queryKey: ['performance-daily', days],
    queryFn: () => fetchDailyPerformance(days),
    refetchOnWindowFocus: false,
  });

  const rows: ChartRow[] = useMemo(() => {
    let cumulative = 0;
    return (query.data ?? []).map((d) => {
      const pnl = Number(d.realizedPnl);
      cumulative += pnl;
      return {
        tradeDate: d.tradeDate,
        realizedPnl: pnl,
        cumulativePnl: cumulative,
        avgSlippageBps: d.avgSlippageBps,
        maxSlippageBps: d.maxSlippageBps,
      };
    });
  }, [query.data]);

  const empty = !query.isLoading && rows.length === 0;

  return (
    <>
      <div className="card" style={{ maxWidth: 1100 }}>
        <div className="page-header-row">
          <h2>성과 추이</h2>
          <div className="row" style={{ marginBottom: 0 }}>
            <select value={days} onChange={(e) => setDays(Number(e.target.value))}>
              {DAY_OPTIONS.map((d) => (
                <option key={d} value={d}>
                  최근 {d}일
                </option>
              ))}
            </select>
            <button className="secondary" onClick={() => query.refetch()} disabled={query.isFetching}>
              {query.isFetching ? '새로고침 중...' : '새로고침'}
            </button>
          </div>
        </div>

        {query.isError && (
          <div className="error-banner">
            성과 추이 조회 실패 — {query.error instanceof Error ? query.error.message : String(query.error)}
          </div>
        )}

        {query.isLoading ? (
          <p className="muted">조회 중...</p>
        ) : empty ? (
          <p className="muted">모의 운영 시작 후 축적됩니다.</p>
        ) : (
          <>
            <h3 className="chart-title">일별 실현손익 &amp; 누적 실현손익</h3>
            <ResponsiveContainer width="100%" height={280}>
              <ComposedChart data={rows} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke={COLORS.grid} />
                <XAxis dataKey="tradeDate" tick={{ fill: COLORS.text, fontSize: 11 }} />
                <YAxis yAxisId="left" tick={{ fill: COLORS.text, fontSize: 11 }} />
                <YAxis yAxisId="right" orientation="right" tick={{ fill: COLORS.text, fontSize: 11 }} />
                <Tooltip
                  contentStyle={TOOLTIP_STYLE}
                  formatter={(value) => (typeof value === 'number' ? value.toLocaleString() : String(value ?? ''))}
                />
                <Legend wrapperStyle={{ fontSize: 12, color: COLORS.text }} />
                <Bar yAxisId="left" dataKey="realizedPnl" name="일별 실현손익" fill={COLORS.bar} />
                <Line
                  yAxisId="right"
                  type="monotone"
                  dataKey="cumulativePnl"
                  name="누적 실현손익"
                  stroke={COLORS.cumulative}
                  strokeWidth={2}
                  dot={false}
                />
              </ComposedChart>
            </ResponsiveContainer>

            <h3 className="chart-title">슬리피지 추이 (bps, 양수=불리)</h3>
            <ResponsiveContainer width="100%" height={240}>
              <ComposedChart data={rows} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke={COLORS.grid} />
                <XAxis dataKey="tradeDate" tick={{ fill: COLORS.text, fontSize: 11 }} />
                <YAxis tick={{ fill: COLORS.text, fontSize: 11 }} />
                <Tooltip
                  contentStyle={TOOLTIP_STYLE}
                  formatter={(value) => (typeof value === 'number' ? `${value.toFixed(2)} bps` : String(value ?? ''))}
                />
                <Legend wrapperStyle={{ fontSize: 12, color: COLORS.text }} />
                <ReferenceLine
                  y={BACKTEST_ASSUMED_AVG_BPS}
                  stroke={COLORS.text}
                  strokeDasharray="4 4"
                  label={{ value: `백테스트 가정 ${BACKTEST_ASSUMED_AVG_BPS}bps`, fill: COLORS.text, fontSize: 11, position: 'insideTopLeft' }}
                />
                <Line type="monotone" dataKey="avgSlippageBps" name="평균 슬리피지" stroke={COLORS.avgSlip} strokeWidth={2} dot={false} />
                <Line type="monotone" dataKey="maxSlippageBps" name="최대 슬리피지" stroke={COLORS.maxSlip} strokeWidth={2} dot={false} />
              </ComposedChart>
            </ResponsiveContainer>
          </>
        )}
      </div>
    </>
  );
}

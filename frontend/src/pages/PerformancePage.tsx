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

// 차트 색상 — index.css의 다크 테마 토큰과 맞춘다(CSS 변수는 SVG 안에서 직접 읽기
// 번거로워 recharts에는 리터럴 색상을 그대로 전달한다).
const COLORS = {
  bar: '#4f8cff',
  cumulative: '#3ecf8e',
  avgSlip: '#4f8cff',
  maxSlip: '#ff5d5d',
  grid: '#2a3550',
  text: '#8a95b0',
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
                  contentStyle={{ background: '#1a2233', border: '1px solid #2a3550', color: '#e6ebf5' }}
                  formatter={(value: number) => value.toLocaleString()}
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
                  contentStyle={{ background: '#1a2233', border: '1px solid #2a3550', color: '#e6ebf5' }}
                  formatter={(value: number) => `${value.toFixed(2)} bps`}
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

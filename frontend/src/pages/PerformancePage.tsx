import { Fragment, useMemo, useState } from 'react';
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
import { fetchDailyPerformance, fetchPerformancePeriods } from '../api';
import { num, pct, pnlClass, signedWon, won } from '../format';
import type { PerformancePeriod } from '../types';

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
  /** 운용 자금 대비 일별 수익률(%) — 그날이 속한 전략 변경 구간의 운용 자금 기준. 구간 밖이면 null. */
  capitalReturnPct: number | null;
  avgSlippageBps: number;
  maxSlippageBps: number;
}

/** 그날이 속한 구간 — 시작일이 그날 이하인 마지막 구간. */
function periodOf(periods: PerformancePeriod[], day: string): PerformancePeriod | undefined {
  let found: PerformancePeriod | undefined;
  for (const p of periods) {
    if (p.from <= day) {
      found = p;
    }
  }
  return found;
}

function ratio(v: string | number | null | undefined): string {
  const n = num(v);
  return n === undefined ? '-' : `${n.toFixed(1)}%`;
}

// 전략 변경 전후 비교 표(2026-11-06, 사용자 "전략 변경 시 전후 수익률 비교 가능한 성과") — 운용 자금이 구간마다 달라
// 금액보다 운용 자금 대비 수익률·거래일당 평균·거래당 수익률(실현손익 ÷ 매도 원가)로 비교한다.
function PeriodTable({ periods }: { periods: PerformancePeriod[] }) {
  const [open, setOpen] = useState<Record<string, boolean>>({});
  return (
    <div style={{ overflowX: 'auto' }}>
      <table>
        <thead>
          <tr>
            <th>구간(변경 내용)</th>
            <th>기간</th>
            <th>운용 자금</th>
            <th>거래일</th>
            <th>실현손익</th>
            <th>운용 자금 대비</th>
            <th>거래일당</th>
            <th>청산</th>
            <th>승률</th>
            <th>거래당 수익률</th>
          </tr>
        </thead>
        <tbody>
          {periods.map((p) => (
            <Fragment key={p.from}>
              <tr>
                <td>
                  <button
                    className="secondary"
                    style={{ padding: '0 6px', marginRight: 6 }}
                    onClick={() => setOpen((o) => ({ ...o, [p.from]: !o[p.from] }))}
                    disabled={p.strategies.length === 0}
                    aria-label={`${p.label} 전략별 ${open[p.from] ? '접기' : '펼치기'}`}
                  >
                    {open[p.from] ? '−' : '+'}
                  </button>
                  {p.label}
                  {p.current && <span className="muted"> (지금)</span>}
                </td>
                <td>
                  {p.from} ~ {p.to}
                </td>
                <td>{p.capitalKrw > 0 ? won(p.capitalKrw) : '-'}</td>
                <td>{p.tradingDays}</td>
                <td className={pnlClass(p.realizedPnl)}>{signedWon(p.realizedPnl)}</td>
                <td className={pnlClass(p.capitalReturnPct)}>{pct(p.capitalReturnPct)}</td>
                <td className={pnlClass(p.dailyAvgReturnPct)}>{pct(p.dailyAvgReturnPct)}</td>
                <td>{p.exits}</td>
                <td>{ratio(p.winRatePct)}</td>
                <td className={pnlClass(p.tradeReturnPct)}>{pct(p.tradeReturnPct)}</td>
              </tr>
              {open[p.from] &&
                p.strategies.map((s) => (
                  <tr key={`${p.from}-${s.strategyId}`} className="muted">
                    <td style={{ paddingLeft: 32 }}>{s.strategyId}</td>
                    <td />
                    <td />
                    <td />
                    <td className={pnlClass(s.realizedPnl)}>{signedWon(s.realizedPnl)}</td>
                    <td className={pnlClass(s.capitalReturnPct)}>{pct(s.capitalReturnPct)}</td>
                    <td />
                    <td>{s.exits}</td>
                    <td>{ratio(s.winRatePct)}</td>
                    <td className={pnlClass(s.tradeReturnPct)}>{pct(s.tradeReturnPct)}</td>
                  </tr>
                ))}
            </Fragment>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export default function PerformancePage() {
  const [days, setDays] = useState<number>(90);

  const periodsQuery = useQuery({
    queryKey: ['performance-periods'],
    queryFn: fetchPerformancePeriods,
    refetchOnWindowFocus: false,
  });
  const periods = useMemo(() => periodsQuery.data ?? [], [periodsQuery.data]);

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
      const capital = periodOf(periods, d.tradeDate)?.capitalKrw ?? 0;
      return {
        tradeDate: d.tradeDate,
        realizedPnl: pnl,
        cumulativePnl: cumulative,
        capitalReturnPct: capital > 0 ? Math.round((pnl / capital) * 100 * 1000) / 1000 : null,
        avgSlippageBps: d.avgSlippageBps,
        maxSlippageBps: d.maxSlippageBps,
      };
    });
  }, [query.data, periods]);

  // 변경 시점 세로선 — 범주 축이라 구간 시작일 이후 첫 거래일에 긋는다(첫 구간은 추이 시작이라 긋지 않는다).
  const changeLines = useMemo(
    () =>
      periods.slice(1).flatMap((p) => {
        const at = rows.find((r) => r.tradeDate >= p.from);
        return at ? [{ x: at.tradeDate, label: p.label }] : [];
      }),
    [periods, rows],
  );

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

        <h3 className="chart-title">전략 변경 전후 비교</h3>
        {periodsQuery.isError ? (
          <div className="error-banner">
            전략 변경 구간 조회 실패 — {periodsQuery.error instanceof Error ? periodsQuery.error.message : String(periodsQuery.error)}
          </div>
        ) : periods.length === 0 ? (
          <p className="muted">{periodsQuery.isLoading ? '조회 중...' : '전략 변경 구간 설정이 없습니다(monitor.performance.periods).'}</p>
        ) : (
          <>
            <PeriodTable periods={periods} />
            <p className="muted">
              실현손익만 셉니다(보유 중 평가손익 제외). 운용 자금 대비 = 실현손익 ÷ 그 구간 운용 자금, 거래일당 = 그 값 ÷ 거래일 수,
              거래당 수익률 = 실현손익 ÷ 매도 원가(금액 규모와 무관). 청산은 종목·매도일마다 1건. + 를 누르면 전략별로 봅니다.
            </p>
          </>
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
                {changeLines.map((c) => (
                  <ReferenceLine
                    key={c.x}
                    yAxisId="left"
                    x={c.x}
                    stroke={COLORS.text}
                    strokeDasharray="4 4"
                    label={{ value: c.label, fill: COLORS.text, fontSize: 10, position: 'insideTopRight' }}
                  />
                ))}
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

            <h3 className="chart-title">운용 자금 대비 일별 수익률 (%, 그날 구간의 운용 자금 기준)</h3>
            <ResponsiveContainer width="100%" height={220}>
              <ComposedChart data={rows} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke={COLORS.grid} />
                <XAxis dataKey="tradeDate" tick={{ fill: COLORS.text, fontSize: 11 }} />
                <YAxis tick={{ fill: COLORS.text, fontSize: 11 }} />
                <Tooltip
                  contentStyle={TOOLTIP_STYLE}
                  formatter={(value) => (typeof value === 'number' ? `${value.toFixed(3)}%` : String(value ?? ''))}
                />
                <ReferenceLine y={0} stroke={COLORS.grid} />
                {changeLines.map((c) => (
                  <ReferenceLine key={c.x} x={c.x} stroke={COLORS.text} strokeDasharray="4 4" />
                ))}
                <Bar dataKey="capitalReturnPct" name="운용 자금 대비 일별 수익률" fill={COLORS.cumulative} />
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

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { fetchOrders } from '../api';
import OrderStatusBadge from '../components/OrderStatusBadge';

const DAY_OPTIONS = [7, 30, 90] as const;

/**
 * 주문 이력 화면(FE-1, PLAN.md ADR-10 확장표).
 *
 * <p>대시보드와 달리 폴링하지 않는다 — 탭 진입 시 1회 조회 + 수동 새로고침 버튼만 제공한다
 * (요구사항: "폴링 불필요"). 기간(days) 선택이 바뀌면 쿼리 키가 바뀌어 자동 재조회된다.
 */
export default function OrdersPage() {
  const [days, setDays] = useState<number>(7);

  const query = useQuery({
    queryKey: ['orders', days],
    queryFn: () => fetchOrders(days),
    refetchOnWindowFocus: false,
  });

  const orders = query.data?.orders ?? [];

  return (
    <div className="card" style={{ maxWidth: 1100 }}>
      <div className="page-header-row">
        <h2>주문·체결 이력</h2>
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
          주문 이력 조회 실패 — {query.error instanceof Error ? query.error.message : String(query.error)}
        </div>
      )}

      <div style={{ overflowX: 'auto' }}>
        <table>
          <thead>
            <tr>
              <th>시각</th>
              <th>종목</th>
              <th>방향</th>
              <th>수량/체결</th>
              <th>가격</th>
              <th>상태</th>
              <th>전략</th>
              <th>ClientOrderId</th>
            </tr>
          </thead>
          <tbody>
            {query.isLoading ? (
              <tr>
                <td colSpan={8}>조회 중...</td>
              </tr>
            ) : orders.length === 0 ? (
              <tr>
                <td colSpan={8} className="muted">
                  선택한 기간 동안 주문 없음
                </td>
              </tr>
            ) : (
              orders.map((o) => (
                <tr key={o.clientOrderId}>
                  <td>{new Date(o.submittedAt).toLocaleString()}</td>
                  <td>{o.symbol}</td>
                  <td className={o.side === 'BUY' ? 'side-buy' : 'side-sell'}>{o.side}</td>
                  <td>
                    {o.quantity} / {o.filledQuantity}
                  </td>
                  <td>{o.limitPrice == null ? '-' : Number(o.limitPrice).toLocaleString()}</td>
                  <td>
                    <OrderStatusBadge status={o.status} />
                  </td>
                  <td>{o.strategyId}</td>
                  <td className="mono">{o.clientOrderId}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

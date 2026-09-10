import type { OrderStatus } from '../types';

// 11상태 뱃지 색상 규칙(요구사항): FILLED=초록, REJECTED/CANCELLED=빨강,
// UNKNOWN=주황(결과 불명 — Reconciliation 대상), 나머지는 중립.
function badgeClass(status: OrderStatus): string {
  if (status === 'FILLED') return 'order-badge order-badge-ok';
  if (status === 'REJECTED' || status === 'CANCELLED') return 'order-badge order-badge-danger';
  if (status === 'UNKNOWN') return 'order-badge order-badge-warn';
  return 'order-badge order-badge-neutral';
}

export default function OrderStatusBadge({ status }: { status: OrderStatus }) {
  return <span className={badgeClass(status)}>{status}</span>;
}

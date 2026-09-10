import type { TradingStatus } from '../types';

interface Props {
  status?: TradingStatus;
  isError: boolean;
  isFetching: boolean;
  dataUpdatedAt: number;
}

export default function Header({ status, isError, dataUpdatedAt }: Props) {
  const badgeClass = status ? `badge badge-${status}` : 'badge badge-STOPPED';
  const lastUpdated = dataUpdatedAt ? new Date(dataUpdatedAt).toLocaleTimeString() : '-';

  return (
    <div>
      <h1>autoStock</h1>
      <span className={badgeClass}>{status ?? (isError ? '연결 실패' : '확인 중...')}</span>
      <span className="last-updated">마지막 갱신: {lastUpdated}</span>
      <div className="sub">경량 대시보드 — 2초 주기 자동 갱신 · SIM 모드 검증용</div>
    </div>
  );
}

import type { TradingStatus } from '../types';

interface Props {
  status?: TradingStatus;
  executionMode?: string;
  isError: boolean;
  isFetching: boolean;
  dataUpdatedAt: number;
}

// Phase 0.5: 실행 모드를 서버 값으로 표시한다(예전 부제 "SIM 모드 검증용"은 LIVE에서도 그대로 나와 오해를 불렀다).
// LIVE면 화면 맨 위에 붉은 띠를 둔다 — 어느 탭에서든 "지금 주문이 브로커로 간다"가 보이게.
export default function Header({ status, executionMode, isError, dataUpdatedAt }: Props) {
  const badgeClass = status ? `badge badge-${status}` : 'badge badge-STOPPED';
  const lastUpdated = dataUpdatedAt ? new Date(dataUpdatedAt).toLocaleTimeString() : '-';

  return (
    <div>
      {executionMode === 'LIVE' && (
        <div className="live-band" role="status">
          LIVE 모드 — 주문이 브로커로 전송됩니다
        </div>
      )}
      <h1>autoStock</h1>
      <span className={badgeClass}>{status ?? (isError ? '연결 실패' : '확인 중...')}</span>
      <span className="last-updated">마지막 갱신: {lastUpdated}</span>
      <div className="sub">
        경량 대시보드 — 2초 주기 자동 갱신 · 실행 모드 {executionMode ?? '확인 중'}
      </div>
    </div>
  );
}

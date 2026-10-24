import type { TradingStatus } from '../types';
import { brokerServerName } from '../format';

interface Props {
  status?: TradingStatus;
  executionMode?: string;
  brokerServer?: string;
  isError: boolean;
  isFetching: boolean;
  dataUpdatedAt: number;
}

// Phase 0.5: 실행 모드를 서버 값으로 표시한다(예전 부제 "SIM 모드 검증용"은 LIVE에서도 그대로 나와 오해를 불렀다).
// LIVE면 화면 맨 위에 띠를 둔다 — 어느 탭에서든 "지금 주문이 브로커로 간다"가 보이게.
// 2026-10-23: LIVE는 "브로커로 보낸다"는 뜻일 뿐이라 접속 서버를 함께 적는다. 모의 서버는 주황 띠, 실서버(실거래)만 붉은 띠.
function liveBandText(brokerServer?: string): string {
  if (brokerServer === 'MOCK') {
    return 'LIVE · 키움 모의 서버 — 주문이 모의투자 계좌로 전송됩니다(실제 돈 아님)';
  }
  if (brokerServer === 'REAL') {
    return 'LIVE · 키움 실서버 — 실제 계좌로 주문이 전송됩니다(실거래)';
  }
  return `LIVE — 주문이 브로커로 전송됩니다(${brokerServerName(brokerServer)})`;
}

export default function Header({ status, executionMode, brokerServer, isError, dataUpdatedAt }: Props) {
  const badgeClass = status ? `badge badge-${status}` : 'badge badge-STOPPED';
  const lastUpdated = dataUpdatedAt ? new Date(dataUpdatedAt).toLocaleTimeString() : '-';

  return (
    <div>
      {executionMode === 'LIVE' && (
        <div className={brokerServer === 'MOCK' ? 'live-band live-band-mock' : 'live-band'} role="status">
          {liveBandText(brokerServer)}
        </div>
      )}
      <h1>autoStock</h1>
      <span className={badgeClass}>{status ?? (isError ? '연결 실패' : '확인 중...')}</span>
      <span className="last-updated">마지막 갱신: {lastUpdated}</span>
      <div className="sub">
        경량 대시보드 — 2초 주기 자동 갱신 · 실행 모드 {executionMode ?? '확인 중'}
        {executionMode === 'LIVE' && ` · ${brokerServerName(brokerServer)}`}
      </div>
    </div>
  );
}

import type { SystemInfo } from '../types';
import { brokerServerName } from '../format';

interface Props {
  system?: SystemInfo;
}

function OnOffDot({ on }: { on: boolean }) {
  return <span className={`dot ${on ? 'dot-on' : 'dot-off'}`} />;
}

export default function SystemCard({ system }: Props) {
  const mode = system?.executionMode;
  const modeClass = mode === 'LIVE' ? 'mode-badge mode-LIVE' : 'mode-badge mode-SIM';

  return (
    <div className="card">
      <h2>시스템</h2>
      <p>
        실행 모드: {mode ? <span className={modeClass}>{mode}</span> : '확인 중...'}
      </p>
      {mode === 'LIVE' && <p style={{ marginTop: 4 }}>접속 서버: {brokerServerName(system?.brokerServer)}</p>}
      <p style={{ marginTop: 8 }}>
        <OnOffDot on={!!system?.c3Enabled} /> C3 전략 {system === undefined ? '-' : system.c3Enabled ? 'ON' : 'OFF'}
      </p>
      <p style={{ marginTop: 4 }}>
        <OnOffDot on={!!system?.wsEnabled} /> WebSocket {system === undefined ? '-' : system.wsEnabled ? 'ON' : 'OFF'}
      </p>
      {mode === 'LIVE' && system?.brokerServer === 'REAL' && (
        <p className="muted warn">LIVE · 실서버 — 실거래가 발생합니다.</p>
      )}
      {mode === 'LIVE' && system?.brokerServer === 'MOCK' && (
        <p className="muted">LIVE · 모의 서버 — 주문은 키움 모의투자 계좌로 갑니다(실제 돈 아님).</p>
      )}
      {mode === 'LIVE' && system?.brokerServer !== 'REAL' && system?.brokerServer !== 'MOCK' && (
        <p className="muted warn">LIVE — 접속 서버를 확인할 수 없습니다. 실거래일 수 있습니다.</p>
      )}
    </div>
  );
}

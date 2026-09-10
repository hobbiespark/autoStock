import type { SystemInfo } from '../types';

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
      <p style={{ marginTop: 8 }}>
        <OnOffDot on={!!system?.c3Enabled} /> C3 전략 {system === undefined ? '-' : system.c3Enabled ? 'ON' : 'OFF'}
      </p>
      <p style={{ marginTop: 4 }}>
        <OnOffDot on={!!system?.wsEnabled} /> WebSocket {system === undefined ? '-' : system.wsEnabled ? 'ON' : 'OFF'}
      </p>
      {mode === 'LIVE' && <p className="muted warn">LIVE 모드 — 실거래가 발생합니다.</p>}
    </div>
  );
}

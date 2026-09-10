import type { TradingInfo } from '../types';

interface Props {
  trading?: TradingInfo;
}

export default function PerformanceCard({ trading }: Props) {
  const pnl = trading ? Number(trading.todayRealizedPnl) : undefined;

  return (
    <div className="card">
      <h2>오늘 실적</h2>
      <p>주문 수: {trading?.todayOrderCount ?? '-'}</p>
      <p>
        실현손익:{' '}
        <span className={pnl !== undefined && pnl >= 0 ? 'pnl-pos' : 'pnl-neg'}>
          {pnl !== undefined ? `${pnl.toLocaleString()}원` : '-'}
        </span>
      </p>
      <p>
        보수 모드(거시 국면):{' '}
        <span className={trading?.conservativeMode ? 'ks-on' : 'ks-off'}>
          {trading === undefined ? '-' : trading.conservativeMode ? 'ON (신규 매수 금지)' : 'OFF (정상)'}
        </span>
      </p>
      <p className="muted">
        실현손익만 반영 — 보유 중인 포지션의 미실현 평가손익은 포함하지 않음(TODO).
        보수 모드는 VIX/환율 임계 초과 시 자동으로 켜지며, 신규 매수만 막고 청산은 그대로 허용합니다.
        일 손실 한도는 -2%를 참고 기준으로 삼습니다.
      </p>
    </div>
  );
}

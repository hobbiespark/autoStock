import type { DailyLossLimitInfo, TradingInfo } from '../types';
import { num, pnlClass, signedWon, won } from '../format';

interface Props {
  trading?: TradingInfo;
}

// 일 손실 한도 한 줄(2026-10-23) — 예전 고정 문구("-2%를 참고 기준") 대신 서버가 지금 쓰는 값을 보인다.
function lossLimitText(limit?: DailyLossLimitInfo | null): string {
  if (!limit) {
    return '일 손실 한도: 확인 중';
  }
  const pctText = `${(limit.lossPct * 100).toFixed(0)}%`;
  if (num(limit.threshold) === undefined) {
    return `일 손실 한도: ${pctText}(기준 금액 확인 중)`;
  }
  return `일 손실 한도: ${pctText} × 기준 ${won(limit.base)} = ${signedWon(limit.threshold)}`;
}

function lossLimitBasis(limit?: DailyLossLimitInfo | null): string {
  if (!limit) {
    return '';
  }
  if (limit.capitalLimited) {
    return `기준 = min(계좌 평가액, 운용 자금 ${won(limit.capitalLimit)})과 오늘 최대 보유액(${won(limit.peakHeld)}) 중 큰 값.`;
  }
  return '기준 = 계좌 평가액.';
}

export default function PerformanceCard({ trading }: Props) {
  const pnl = trading ? num(trading.todayRealizedPnl) : undefined;
  const limit = trading?.dailyLossLimit;

  return (
    <div className="card">
      <h2>오늘 실적</h2>
      <p>주문 수: {trading?.todayOrderCount ?? '-'}</p>
      <p>
        실현손익:{' '}
        <span className={pnlClass(pnl)}>{pnl !== undefined ? signedWon(pnl) : '-'}</span>
      </p>
      <p>
        {lossLimitText(limit)}
        {limit && (
          <>
            {' '}
            · 오늘 한도용 손익 <span className={pnlClass(limit.pnlForLimit)}>{signedWon(limit.pnlForLimit)}</span>
          </>
        )}
      </p>
      <p>
        보수 모드(거시 국면):{' '}
        <span className={trading?.conservativeMode ? 'ks-on' : 'ks-off'}>
          {trading === undefined ? '-' : trading.conservativeMode ? 'ON (신규 매수 금지)' : 'OFF (정상)'}
        </span>
      </p>
      <p>
        공시 매수 금지 종목: <span>{trading?.disclosureBlacklistCount ?? '-'}</span>건
      </p>
      <p className="muted">
        실현손익만 반영 — 보유 중인 포지션의 미실현 평가손익은 포함하지 않음(TODO).
        보수 모드는 VIX/환율 임계 초과 시 자동으로 켜지며, 신규 매수만 막고 청산은 그대로 허용합니다.
        한도용 손익은 밤사이 보유분을 전일 종가로 잽니다. {lossLimitBasis(limit)}
      </p>
    </div>
  );
}

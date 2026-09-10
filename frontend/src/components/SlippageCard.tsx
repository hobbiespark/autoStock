import type { SlippageInfo } from '../types';

interface Props {
  slippage?: SlippageInfo;
}

// 백테스트 가정치(평균 5bps)를 초과하면 실거래 비용이 시뮬레이션보다 나쁘다는 뜻이므로 경고색으로 표시한다.
const BACKTEST_ASSUMED_AVG_BPS = 5;

export default function SlippageCard({ slippage }: Props) {
  const hasFills = slippage !== undefined && slippage.fills > 0;
  const overBudget = hasFills && slippage!.avgBps > BACKTEST_ASSUMED_AVG_BPS;

  return (
    <div className="card">
      <h2>슬리피지</h2>
      {slippage === undefined ? (
        <p>확인 중...</p>
      ) : !hasFills ? (
        <p className="muted">체결 없음</p>
      ) : (
        <>
          <p>체결 건수: {slippage.fills}</p>
          <p>
            평균:{' '}
            <span className={overBudget ? 'warn' : 'ok-text'} title="양수=불리(체결가가 기준가보다 불리한 방향)">
              {slippage.avgBps.toFixed(1)} bps
            </span>
          </p>
          <p title="양수=불리(체결가가 기준가보다 불리한 방향)">
            최대: {slippage.maxBps.toFixed(1)} bps ({slippage.maxBpsSymbol})
          </p>
          <p className="muted">
            백테스트 가정 평균 {BACKTEST_ASSUMED_AVG_BPS}bps 초과 시 경고 — 양수는 체결가가 기준가보다 불리했다는 뜻입니다.
          </p>
        </>
      )}
    </div>
  );
}

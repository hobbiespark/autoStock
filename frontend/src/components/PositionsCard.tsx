import type { AccountInfo, Position } from '../types';
import { num, pct, pnlClass, price, signedWon, won } from '../format';
import StockLabel from './StockLabel';

interface Props {
  positions?: Position[];
  account?: AccountInfo | null;
}

// 보유 포지션과 계좌 잔고(2026-10-23 "계좌 잔고 표시(보유종목 없으면 계좌금액) 되어야 함").
// 계좌 금액은 키움 잔고(추정예탁자산)를 서버가 30초마다 읽은 값이다. 수량·평단은 앱 장부(체결 즉시), 현재가는 잔고 기준이다.
function AccountSummary({ account, empty }: { account?: AccountInfo | null; empty: boolean }) {
  if (account === undefined) {
    return <p className="muted">계좌 잔고 확인 중...</p>;
  }
  if (account === null) {
    return null;
  }
  const asOf = account.asOf ? new Date(account.asOf).toLocaleTimeString() : null;
  const pending = num(account.capitalPendingBuy);
  const used = (num(account.capitalHeldCost) ?? 0) + (pending ?? 0);
  return (
    <div className="account-summary">
      {account.available ? (
        <>
          <p className="account-total">
            계좌 총자산 <strong>{won(account.totalAsset)}</strong>
            {empty && <span className="muted-inline"> — 보유 없음(전액 현금)</span>}
          </p>
          {!empty && (
            <p>
              보유 평가 {won(account.holdingsValue)} · 매입 {won(account.holdingsCost)} · 평가손익{' '}
              <span className={pnlClass(account.holdingsPnl)}>{signedWon(account.holdingsPnl)}</span>
            </p>
          )}
        </>
      ) : (
        <p className="muted-inline">계좌 금액: {account.note ?? '확인 중'}</p>
      )}
      {account.capitalLimitKrw > 0 && (
        <p>
          운용 자금 사용 {won(used)} / {won(account.capitalLimitKrw)}
          <span className="muted-inline">
            {' '}
            (보유 원가 {won(account.capitalHeldCost)} + 미체결 매수 {pending === undefined ? '확인 불가' : won(pending)})
          </span>
        </p>
      )}
      {account.available && (
        <p className="muted">
          키움 잔고 기준{asOf ? ` ${asOf}` : ''}(30초마다){account.note ? ` · ${account.note}` : ''}
        </p>
      )}
    </div>
  );
}

export default function PositionsCard({ positions, account }: Props) {
  const empty = positions !== undefined && positions.length === 0;
  return (
    <div className="card">
      <h2>보유 포지션 · 계좌</h2>
      <AccountSummary account={account} empty={empty} />
      <table>
        <thead>
          <tr>
            <th>종목</th>
            <th>수량</th>
            <th>평균단가</th>
            <th>현재가</th>
            <th>평가손익</th>
            <th>수익률</th>
          </tr>
        </thead>
        <tbody>
          {positions === undefined ? (
            <tr>
              <td colSpan={6}>-</td>
            </tr>
          ) : positions.length === 0 ? (
            <tr>
              <td colSpan={6}>
                보유 없음{account?.available ? ` — 계좌 총자산 ${won(account.totalAsset)}` : ''}
              </td>
            </tr>
          ) : (
            positions.map((p) => (
              <tr key={p.symbol}>
                <td>
                  <StockLabel code={p.symbol} name={p.symbolName} />
                </td>
                <td>{p.quantity}</td>
                <td>{p.avgPrice == null ? '—' : price(p.avgPrice)}</td>
                <td>{p.currentPrice == null ? '—' : price(p.currentPrice)}</td>
                <td className={pnlClass(p.evaluationPnl)}>
                  {p.evaluationPnl == null ? '—' : signedWon(p.evaluationPnl)}
                </td>
                <td className={pnlClass(p.returnPct)}>{p.returnPct == null ? '—' : pct(p.returnPct)}</td>
              </tr>
            ))
          )}
        </tbody>
      </table>
      <p className="muted">평가손익·수익률은 현재가 기준(수수료·거래세 전)입니다.</p>
    </div>
  );
}

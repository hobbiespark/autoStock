import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { sendTestSignal } from '../api';
import type { Side } from '../types';

// 테스트 시그널 카드. Signal → RiskGate → 주문 → SIM 체결 → 포지션 갱신, 실제 매매와 동일 경로.
export default function TestSignalCard() {
  const [symbol, setSymbol] = useState('005930');
  const [side, setSide] = useState<Side>('BUY');
  const [price, setPrice] = useState('70000');
  const queryClient = useQueryClient();

  const mutation = useMutation({
    mutationFn: () => sendTestSignal(symbol.trim(), side, price.trim()),
    onSettled: () => {
      // SIM 체결이 반영될 시간을 살짝 준 뒤 재조회한다(낙관적 업데이트 없음).
      setTimeout(() => queryClient.invalidateQueries({ queryKey: ['dashboard'] }), 300);
    },
  });

  return (
    <div className="card">
      <h2>테스트 시그널 (SIM 루프 검증)</h2>
      <div className="row">
        <input
          value={symbol}
          onChange={(e) => setSymbol(e.target.value)}
          placeholder="종목코드 예: 005930"
        />
        <select value={side} onChange={(e) => setSide(e.target.value as Side)}>
          <option value="BUY">BUY</option>
          <option value="SELL">SELL</option>
        </select>
        <input value={price} onChange={(e) => setPrice(e.target.value)} placeholder="기준가" />
      </div>
      <button disabled={mutation.isPending} onClick={() => mutation.mutate()}>
        시그널 발행
      </button>
      <p className="muted">Signal → RiskGate → 주문 → SIM 체결 → 포지션 갱신, 실제 매매와 동일 경로.</p>
    </div>
  );
}

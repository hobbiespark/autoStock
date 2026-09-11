import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchQuote, sendTestSignal } from '../api';
import type { Side } from '../types';

// 테스트 시그널 카드. Signal → RiskGate → 주문 → 체결 → 포지션 갱신, 실제 매매와 동일 경로.
// 운영 1일차 ③⑦⑧: 종목 입력 시 시세(시/고/저/현재가+호가) 표시, 기준가는 현재가로 채움,
// 수량은 선택 입력(비우면 자동 사이징 — 매수: 예산 비율, 매도: 전량 청산).
export default function TestSignalCard() {
  const [symbol, setSymbol] = useState('005930');
  const [side, setSide] = useState<Side>('BUY');
  const [price, setPrice] = useState('');
  const [quantity, setQuantity] = useState('');
  const [priceTouched, setPriceTouched] = useState(false);
  const queryClient = useQueryClient();

  const validSymbol = /^\d{6}$/.test(symbol.trim());
  const quote = useQuery({
    queryKey: ['quote', symbol.trim()],
    queryFn: () => fetchQuote(symbol.trim()),
    enabled: validSymbol,
    refetchInterval: 5000, // 폼이 떠 있는 동안 5초 주기 — 서버 캐시 TTL 1초라 부담 없음
    refetchOnWindowFocus: false,
  });

  // 기준가: 사용자가 직접 수정하기 전에는 현재가를 따라간다(운영 1일차 ③ — 70,000 고정
  // 기본값이 RC4027 가격제한폭 거부를 유발했던 결함의 수정).
  const currentPrice = quote.data?.currentPrice;
  const effectivePrice =
    priceTouched || currentPrice == null ? price : String(currentPrice);

  const mutation = useMutation({
    mutationFn: () => sendTestSignal(symbol.trim(), side, effectivePrice.trim(), quantity.trim()),
    onSettled: () => {
      // 체결 반영 시간을 살짝 준 뒤 재조회한다(낙관적 업데이트 없음).
      setTimeout(() => {
        queryClient.invalidateQueries({ queryKey: ['dashboard'] });
        queryClient.invalidateQueries({ queryKey: ['orders'] });
      }, 500);
    },
  });

  const fmt = (v: string | number | null | undefined) =>
    v == null ? '-' : Number(v).toLocaleString();

  return (
    <div className="card">
      <h2>테스트 시그널 (수동 주문)</h2>
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
        <input
          value={effectivePrice}
          onChange={(e) => {
            setPriceTouched(true);
            setPrice(e.target.value);
          }}
          placeholder="기준가 (자동: 현재가)"
        />
        <input
          value={quantity}
          onChange={(e) => setQuantity(e.target.value)}
          placeholder="수량 (비우면 자동)"
          style={{ maxWidth: 140 }}
        />
      </div>
      {validSymbol && (
        <p className="muted" style={{ margin: '4px 0' }}>
          {quote.isError
            ? '시세 조회 실패'
            : quote.data
              ? `${quote.data.name ?? symbol.trim()} · 현재가 ${fmt(quote.data.currentPrice)} · 시 ${fmt(quote.data.openPrice)} · 고 ${fmt(
                  quote.data.highPrice,
                )} · 저 ${fmt(quote.data.lowPrice)} · 매도호가 ${fmt(quote.data.bestAsk)} · 매수호가 ${fmt(
                  quote.data.bestBid,
                )}`
              : '시세 조회 중...'}
        </p>
      )}
      <button
        disabled={mutation.isPending || effectivePrice.trim() === ''}
        onClick={() => mutation.mutate()}
      >
        시그널 발행
      </button>
      <p className="muted">
        Signal → RiskGate → 주문 → 체결 → 포지션 갱신, 실제 매매와 동일 경로. 수량을 지정해도
        리스크 상한(매수 예산 캡·매도 보유량 캡)을 넘지 않는다.
      </p>
    </div>
  );
}

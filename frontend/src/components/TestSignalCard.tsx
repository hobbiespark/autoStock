import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { describeError, fetchQuote, sendTestSignal } from '../api';
import type { Side, SystemInfo } from '../types';
import ConfirmDialog from './ConfirmDialog';

// 백엔드 검증(DashboardController.TestSignalRequest)과 같은 규칙.
const QUANTITY_PATTERN = /^[1-9]\d{0,8}$/;
const PRICE_PATTERN = /^[1-9]\d{0,8}(\.\d{1,4})?$/;

interface Props {
  system?: SystemInfo;
}

// 테스트 시그널 카드. Signal → RiskGate → 주문 → 체결 → 포지션 갱신, 실제 매매와 동일 경로.
// 운영 1일차 ③⑧: 종목 입력 시 시세(시/고/저/현재가+호가) 표시, 기준가는 현재가로 채움.
// Phase 0.5(D-06, aiDoc/manual-order-guard.md) — 2026-09-18 "기본 종목 선입력 + 수량 공란 자동 사이징 + 1클릭"으로
// 193주가 즉시 체결된 사고의 재발 방지: 종목 기본값 없음, 수량 필수, 발행 전 확인 창(예상 금액·실행 모드),
// LIVE면 종목코드 재입력, 매수 금액 상한 초과면 발행 불가, 서버 오류 표시.
export default function TestSignalCard({ system }: Props) {
  const [symbol, setSymbol] = useState('');
  const [side, setSide] = useState<Side>('BUY');
  const [price, setPrice] = useState('');
  const [quantity, setQuantity] = useState('');
  const [priceTouched, setPriceTouched] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [retyped, setRetyped] = useState('');
  const queryClient = useQueryClient();

  const trimmedSymbol = symbol.trim();
  const validSymbol = /^\d{6}$/.test(trimmedSymbol);
  const quote = useQuery({
    queryKey: ['quote', trimmedSymbol],
    queryFn: () => fetchQuote(trimmedSymbol),
    enabled: validSymbol,
    refetchInterval: 5000, // 폼이 떠 있는 동안 5초 주기 — 서버 캐시 TTL 1초라 부담 없음
    refetchOnWindowFocus: false,
  });

  // 기준가: 사용자가 직접 수정하기 전에는 현재가를 따라간다(운영 1일차 ③ — 70,000 고정
  // 기본값이 RC4027 가격제한폭 거부를 유발했던 결함의 수정).
  const currentPrice = quote.data?.currentPrice;
  const effectivePrice = (priceTouched || currentPrice == null ? price : String(currentPrice)).trim();
  const trimmedQuantity = quantity.trim();

  const mode = system?.executionMode;
  const isLive = mode === 'LIVE';
  const maxKrw = system?.manualOrderMaxKrw;
  const validPrice = PRICE_PATTERN.test(effectivePrice);
  const validQuantity = QUANTITY_PATTERN.test(trimmedQuantity);
  const amount = validPrice && validQuantity ? Number(effectivePrice) * Number(trimmedQuantity) : null;
  // 매수만 금액 상한(매도는 보유량 캡 — 청산을 막지 않는다). 판정은 RiskGate, 여기서는 미리 막아 헛발행을 없앤다.
  const overCap = side === 'BUY' && amount != null && maxKrw != null && amount > maxKrw;
  const retypeOk = !isLive || retyped.trim() === trimmedSymbol;
  const canReview = validSymbol && validPrice && validQuantity && mode !== undefined;

  const mutation = useMutation({
    mutationFn: () => sendTestSignal(trimmedSymbol, side, effectivePrice, trimmedQuantity),
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
  const stockName = quote.data?.name ?? (quote.isError ? '종목명 조회 실패' : '종목명 조회 중');

  const openReview = () => {
    mutation.reset();
    setRetyped('');
    setConfirming(true);
  };
  const confirm = () => {
    setConfirming(false);
    mutation.mutate();
  };

  return (
    <div className="card">
      <h2>테스트 시그널 (수동 주문)</h2>
      {isLive && (
        <p className="live-inline" role="note">
          LIVE 모드 — 이 카드의 주문은 브로커로 전송됩니다.
        </p>
      )}
      <div className="row">
        <input
          aria-label="종목코드(6자리)"
          value={symbol}
          onChange={(e) => setSymbol(e.target.value)}
          placeholder="종목코드 6자리 (예: 005930)"
          inputMode="numeric"
          maxLength={6}
        />
        <select aria-label="매매 방향" value={side} onChange={(e) => setSide(e.target.value as Side)}>
          <option value="BUY">BUY (매수)</option>
          <option value="SELL">SELL (매도)</option>
        </select>
        <input
          aria-label="기준가(원)"
          value={priceTouched || currentPrice == null ? price : String(currentPrice)}
          onChange={(e) => {
            setPriceTouched(true);
            setPrice(e.target.value);
          }}
          placeholder="기준가 (자동: 현재가)"
          inputMode="decimal"
        />
        <input
          aria-label="수량(주, 필수)"
          aria-required="true"
          value={quantity}
          onChange={(e) => setQuantity(e.target.value)}
          placeholder="수량 (필수)"
          inputMode="numeric"
          style={{ maxWidth: 140 }}
        />
      </div>
      {validSymbol && (
        <p className="muted" style={{ margin: '4px 0' }}>
          {quote.isError
            ? '시세 조회 실패'
            : quote.data
              ? `${quote.data.name ?? trimmedSymbol} · 현재가 ${fmt(quote.data.currentPrice)} · 시 ${fmt(quote.data.openPrice)} · 고 ${fmt(
                  quote.data.highPrice,
                )} · 저 ${fmt(quote.data.lowPrice)} · 매도호가 ${fmt(quote.data.bestAsk)} · 매수호가 ${fmt(
                  quote.data.bestBid,
                )}`
              : '시세 조회 중...'}
        </p>
      )}
      {amount != null && (
        <p className={overCap ? 'warn' : 'muted'} style={{ margin: '4px 0' }}>
          예상 금액 {fmt(amount)}원
          {overCap && ` — 수동 매수 1건 상한 ${fmt(maxKrw)}원 초과(발행 불가)`}
        </p>
      )}
      <button disabled={!canReview || overCap || mutation.isPending} onClick={openReview}>
        발행 전 확인
      </button>
      {mutation.isError && (
        <p className="error-text" role="alert">
          발행 실패: {describeError(mutation.error)}
        </p>
      )}
      {mutation.isSuccess && (
        <p className="ok-text" role="status" style={{ marginTop: 8 }}>
          발행됨({mutation.data.executionMode} 모드) — RiskGate 판정은 이벤트 피드·판단 근거 탭에서 확인
        </p>
      )}
      <p className="muted">
        Signal → RiskGate → 주문 → 체결 → 포지션 갱신, 실제 매매와 동일 경로. 수량은 필수이고, 리스크 상한(매수 금액
        상한·예산 캡, 매도 보유량 캡)을 넘지 않는다.
      </p>

      <ConfirmDialog
        open={confirming}
        title={isLive ? '실주문 발행 확인 (LIVE)' : '테스트 시그널 발행 확인'}
        confirmLabel="발행"
        danger={isLive}
        confirmDisabled={!retypeOk || overCap}
        onConfirm={confirm}
        onCancel={() => setConfirming(false)}
      >
        <dl className="confirm-summary">
          <dt>종목</dt>
          <dd>
            {stockName} ({trimmedSymbol})
          </dd>
          <dt>방향</dt>
          <dd className={side === 'BUY' ? 'pnl-pos' : 'pnl-neg'}>{side === 'BUY' ? '매수' : '매도'}</dd>
          <dt>기준가</dt>
          <dd>{fmt(effectivePrice)}원</dd>
          <dt>수량</dt>
          <dd>{fmt(trimmedQuantity)}주</dd>
          <dt>예상 금액</dt>
          <dd>{fmt(amount)}원</dd>
          <dt>실행 모드</dt>
          <dd>{mode ?? '-'}</dd>
        </dl>
        {isLive && (
          <div className="live-confirm">
            <p>LIVE 모드 — 브로커로 주문이 전송됩니다. 확인을 위해 종목코드를 다시 입력하세요.</p>
            <input
              aria-label="확인용 종목코드 재입력"
              value={retyped}
              onChange={(e) => setRetyped(e.target.value)}
              placeholder="종목코드 6자리"
              inputMode="numeric"
              maxLength={6}
            />
          </div>
        )}
      </ConfirmDialog>
    </div>
  );
}

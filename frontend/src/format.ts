// 금액·비율 표기(2026-10-23) — 원 단위 반올림(예전엔 실현손익이 "11,405.429원"처럼 소수로 나왔다).

type Num = string | number | null | undefined;

/** 숫자로 — 비었거나 숫자가 아니면 undefined. */
export function num(v: Num): number | undefined {
  if (v === null || v === undefined || v === '') {
    return undefined;
  }
  const n = Number(v);
  return Number.isFinite(n) ? n : undefined;
}

/** "1,234,567원"(원 단위 반올림). 모르면 "-". */
export function won(v: Num): string {
  const n = num(v);
  return n === undefined ? '-' : `${Math.round(n).toLocaleString('ko-KR')}원`;
}

/** 이익이면 + 부호를 붙인 원 표기. */
export function signedWon(v: Num): string {
  const n = num(v);
  if (n === undefined) {
    return '-';
  }
  const r = Math.round(n);
  return `${r > 0 ? '+' : ''}${r.toLocaleString('ko-KR')}원`;
}

/** 단가 — 정수면 쉼표만, 소수면 둘째 자리까지. */
export function price(v: Num): string {
  const n = num(v);
  if (n === undefined) {
    return '-';
  }
  return Number.isInteger(n) ? n.toLocaleString('ko-KR') : n.toLocaleString('ko-KR', { maximumFractionDigits: 2 });
}

/** 부호 있는 백분율 — 이미 % 단위인 값(예: 4.87 → "+4.87%"). */
export function pct(v: Num): string {
  const n = num(v);
  return n === undefined ? '-' : `${n > 0 ? '+' : ''}${n.toFixed(2)}%`;
}

/** 손익 색 — 양수·0은 초록, 음수는 빨강, 모르면 없음. */
export function pnlClass(v: Num): string | undefined {
  const n = num(v);
  if (n === undefined) {
    return undefined;
  }
  return n >= 0 ? 'pnl-pos' : 'pnl-neg';
}

/** 키움 접속 서버 이름. */
export function brokerServerName(server?: string): string {
  if (server === 'MOCK') {
    return '키움 모의 서버';
  }
  if (server === 'REAL') {
    return '키움 실서버';
  }
  return '접속 서버 확인 불가';
}

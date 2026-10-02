// 종목 표기(2026-10-02 "종목코드와 종목명은 항상 같이 표시", aiDoc/stock-names.md).
// 백엔드 common.util.StockNames.label과 같은 형식 "삼성전자(005930)"을 쓴다 — 텔레그램 알림·이벤트 피드(서버가 만든
// 문구)와 화면이 같은 모양이 된다. 이름을 모르면 "종목명 미확인(005930)".

export const UNKNOWN_STOCK_NAME = '종목명 미확인';

/** 종목명. 비어 있으면 "종목명 미확인". */
export function stockName(name?: string | null): string {
  const trimmed = name?.trim();
  return trimmed ? trimmed : UNKNOWN_STOCK_NAME;
}

/** "종목명(코드)" 한 줄 표기 — 확인 창·문구용. */
export function stockLabel(code: string, name?: string | null): string {
  return `${stockName(name)}(${code})`;
}

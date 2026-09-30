import type {
  DailyPerformance,
  DashboardView,
  DecisionItem,
  IpoDeal,
  IpoMetricsInput,
  IpoRecordInput,
  OrderHistoryView,
  QuoteView,
  Side,
  TestSignalResponse,
} from './types';

// 서버 오류(RFC 9457 ProblemDetail, application/problem+json)를 화면에 보여줄 수 있게 옮겨 담는다(Phase 0.5).
// 예전에는 "요청 실패: HTTP 400"만 던져 무엇이 틀렸는지 알 수 없었다.
export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly fieldErrors: { field: string; message: string }[];
  readonly currentStatus?: string;

  constructor(status: number, message: string, code?: string,
              fieldErrors: { field: string; message: string }[] = [], currentStatus?: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.fieldErrors = fieldErrors;
    this.currentStatus = currentStatus;
  }
}

async function toApiError(res: Response): Promise<ApiError> {
  const type = res.headers.get('Content-Type') ?? '';
  if (type.includes('json')) {
    try {
      const body = await res.json();
      const errors = Array.isArray(body.errors) ? body.errors : [];
      return new ApiError(res.status, body.detail ?? body.title ?? `HTTP ${res.status}`, body.code, errors,
        body.currentStatus);
    } catch {
      // 본문이 JSON이 아니면 상태 코드만으로 만든다
    }
  }
  return new ApiError(res.status, `요청 실패: HTTP ${res.status}`);
}

// 명령 카드의 오류 문구 — 서버가 준 설명 + 필드 오류 + 현재 상태(409).
export function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    const fields = error.fieldErrors.map((f) => `${f.field}: ${f.message}`).join(', ');
    const state = error.currentStatus ? ` (현재 상태: ${error.currentStatus})` : '';
    return `${error.message}${fields ? ` — ${fields}` : ''}${state} [HTTP ${error.status}]`;
  }
  return error instanceof Error ? error.message : String(error);
}

// 대시보드는 /api/dashboard 하나만 폴링한다(ARCHITECTURE.md 10절 CQRS Lite —
// 여러 GET을 각자 부르지 않고 서버가 조합한 DashboardView 하나를 받는다).
export async function fetchDashboard(): Promise<DashboardView> {
  const res = await fetch('/api/dashboard');
  if (!res.ok) {
    throw new Error(`대시보드 조회 실패: HTTP ${res.status}`);
  }
  return res.json();
}

// 주문 이력(FE-1) — 기간(days) 필터, 폴링 없이 탭 진입/수동 새로고침 시에만 호출.
export async function fetchOrders(days: number): Promise<OrderHistoryView> {
  const res = await fetch(`/api/orders?days=${days}`);
  if (!res.ok) {
    throw new Error(`주문 이력 조회 실패: HTTP ${res.status}`);
  }
  return res.json();
}

// 일별 성과 추이(FE-2).
export async function fetchDailyPerformance(days: number): Promise<DailyPerformance[]> {
  const res = await fetch(`/api/performance/daily?days=${days}`);
  if (!res.ok) {
    throw new Error(`성과 추이 조회 실패: HTTP ${res.status}`);
  }
  return res.json();
}

// 종목 선정 이유(FE-6) — 날짜 지정 조회(YYYY-MM-DD), 미지정 시 서버가 KST 오늘로 조회.
// 폴링 없이(주문 이력·성과와 동일 관례) 탭 진입/날짜 변경/수동 새로고침 시에만 호출.
export async function fetchDecisions(date?: string): Promise<DecisionItem[]> {
  const qs = date ? `?date=${date}` : '';
  const res = await fetch(`/api/decisions${qs}`);
  if (!res.ok) {
    throw new Error(`판단 근거 조회 실패: HTTP ${res.status}`);
  }
  return res.json();
}

// 공모주(FE-3) — GET /api/ipo?status=, 폴링 없이 탭 진입/필터 변경/수동 새로고침 시에만 호출.
export async function fetchIpoDeals(status?: string): Promise<IpoDeal[]> {
  const qs = status ? `?status=${status}` : '';
  const res = await fetch(`/api/ipo${qs}`);
  if (!res.ok) {
    throw new Error(`공모주 목록 조회 실패: HTTP ${res.status}`);
  }
  return res.json();
}

export async function postIpoRecord(id: number, body: IpoRecordInput): Promise<IpoDeal> {
  const res = await postJson(`/api/ipo/${id}/record`, body);
  return res.json();
}

export async function postIpoMetrics(id: number, body: IpoMetricsInput): Promise<IpoDeal> {
  const res = await postJson(`/api/ipo/${id}/metrics`, body);
  return res.json();
}

async function postJson(path: string, body?: unknown): Promise<Response> {
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body ?? {}),
  });
  if (!res.ok) {
    // 400(형식)·409(상태 부적합) 등 — 호출부가 describeError로 화면에 표시한다(role="alert").
    throw await toApiError(res);
  }
  return res;
}

// 종목 시세(운영 1일차 ③·⑧) — 시/고/저/현재가 + 최우선 매수/매도 호가. 폼 종목 입력 시 호출.
export async function fetchQuote(symbol: string): Promise<QuoteView> {
  const res = await fetch(`/api/dashboard/quote/${symbol}`);
  if (!res.ok) {
    throw new Error(`시세 조회 실패: HTTP ${res.status}`);
  }
  return res.json();
}

export const startTrading = () => postJson('/api/trading/start');
export const stopTrading = () => postJson('/api/trading/stop');
export const setKillSwitch = (engage: boolean) =>
  postJson('/api/dashboard/killswitch', { engage });
// quantity는 필수(Phase 0.4 — 빈 값 자동 사이징 폐지, 2026-09-18 193주 사고). 응답에 실행 모드가 온다.
export async function sendTestSignal(symbol: string, side: Side, price: string, quantity: string): Promise<TestSignalResponse> {
  const res = await postJson('/api/dashboard/test-signal', { symbol, side, price, quantity });
  return res.json();
}
// 주문 취소(운영 1일차 ⑤, kt10003 실측 확정) — 비동기 처리라 잠시 후 이력 재조회 필요.
export const cancelOrder = (clientOrderId: string) =>
  postJson(`/api/dashboard/orders/${clientOrderId}/cancel`);

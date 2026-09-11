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
} from './types';

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
    // 409 등 상태 부적합 오류도 여기서 던진다 — 호출부에서 무시하거나 표시.
    throw new Error(`요청 실패: HTTP ${res.status}`);
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
// quantity(운영 1일차 ⑦): 빈 문자열이면 자동 사이징(매수: 예산 비율, 매도: 전량 청산).
export const sendTestSignal = (symbol: string, side: Side, price: string, quantity: string) =>
  postJson('/api/dashboard/test-signal', { symbol, side, price, quantity });
// 주문 취소(운영 1일차 ⑤, kt10003 실측 확정) — 비동기 처리라 잠시 후 이력 재조회 필요.
export const cancelOrder = (clientOrderId: string) =>
  postJson(`/api/dashboard/orders/${clientOrderId}/cancel`);

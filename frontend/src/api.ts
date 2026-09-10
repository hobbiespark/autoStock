import type { DailyPerformance, DashboardView, DecisionItem, OrderHistoryView, Side } from './types';

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

export const startTrading = () => postJson('/api/trading/start');
export const stopTrading = () => postJson('/api/trading/stop');
export const setKillSwitch = (engage: boolean) =>
  postJson('/api/dashboard/killswitch', { engage });
export const sendTestSignal = (symbol: string, side: Side, price: string) =>
  postJson('/api/dashboard/test-signal', { symbol, side, price });

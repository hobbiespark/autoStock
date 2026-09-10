// 백엔드 DashboardView와 1:1로 대응하는 타입 정의.
// 백엔드 코드는 수정하지 않으므로, 여기서는 API가 실제로 내려주는 형태를 그대로 따른다.

export type TradingStatus =
  | 'STOPPED'
  | 'STARTING'
  | 'RUNNING'
  | 'STOPPING'
  | 'DEGRADED'
  | 'ERROR';

export type EventType = 'SIGNAL' | 'ORDER' | 'FILL' | string;

export interface Position {
  symbol: string;
  quantity: number;
  avgPrice: string | number;
}

export interface DashboardEvent {
  type: EventType;
  summary: string;
  at: string;
}

export interface TradingInfo {
  status: TradingStatus;
  killSwitchEngaged: boolean;
  todayOrderCount: number;
  todayRealizedPnl: string | number;
  conservativeMode: boolean;
}

export interface SystemInfo {
  executionMode: string;
  c3Enabled: boolean;
  wsEnabled: boolean;
}

export interface SlippageInfo {
  fills: number;
  avgBps: number;
  maxBps: number;
  maxBpsSymbol: string;
}

export interface DashboardView {
  positions: Position[];
  recentEvents: DashboardEvent[];
  trading: TradingInfo;
  system: SystemInfo;
  slippage: SlippageInfo;
}

export type Side = 'BUY' | 'SELL';

// 주문 상태 11종 (OrderStatus, 백엔드 trading/OrderStatus.java와 1:1 대응).
export type OrderStatus =
  | 'CREATED'
  | 'VALIDATED'
  | 'SUBMITTING'
  | 'SUBMITTED'
  | 'ACCEPTED'
  | 'PARTIALLY_FILLED'
  | 'FILLED'
  | 'CANCEL_REQUESTED'
  | 'CANCELLED'
  | 'REJECTED'
  | 'UNKNOWN';

// 주문 이력 화면(FE-1) — GET /api/orders 응답 원소.
export interface OrderHistoryItem {
  clientOrderId: string;
  symbol: string;
  side: Side;
  quantity: number;
  filledQuantity: number;
  limitPrice: string | number | null;
  status: OrderStatus;
  strategyId: string;
  submittedAt: string;
}

export interface OrderHistoryView {
  orders: OrderHistoryItem[];
}

// 성과 추이 화면(FE-2) — GET /api/performance/daily 응답 원소.
export interface DailyPerformance {
  tradeDate: string;
  realizedPnl: string | number;
  orderCount: number;
  fillCount: number;
  avgSlippageBps: number;
  maxSlippageBps: number;
  conservativeMode: boolean;
  killSwitchEngaged: boolean;
}

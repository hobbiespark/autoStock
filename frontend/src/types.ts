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

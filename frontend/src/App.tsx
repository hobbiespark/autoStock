import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { fetchDashboard } from './api';
import Header from './components/Header';
import OperationCard from './components/OperationCard';
import KillSwitchCard from './components/KillSwitchCard';
import PerformanceCard from './components/PerformanceCard';
import SlippageCard from './components/SlippageCard';
import SystemCard from './components/SystemCard';
import TestSignalCard from './components/TestSignalCard';
import PositionsCard from './components/PositionsCard';
import EventFeedCard from './components/EventFeedCard';
import TabNav, { type Tab } from './components/TabNav';
import OrdersPage from './pages/OrdersPage';
import PerformancePage from './pages/PerformancePage';
import DecisionsPage from './pages/DecisionsPage';
import IpoPage from './pages/IpoPage';

export default function App() {
  // 화면 3개(대시보드/주문 이력/성과) — 라우터 라이브러리 없이 탭 상태로 전환한다
  // (PLAN.md ADR-10 확장표: 번들 비대화를 피하려 react-router는 아직 도입하지 않음).
  const [tab, setTab] = useState<Tab>('dashboard');

  // 대시보드는 활성 탭일 때만 2초 폴링한다 — 다른 탭을 보는 동안 불필요한 요청을 줄인다.
  const query = useQuery({
    queryKey: ['dashboard'],
    queryFn: fetchDashboard,
    refetchInterval: tab === 'dashboard' ? 2000 : false,
  });

  const view = query.data;

  return (
    <>
      <Header status={view?.trading.status} isError={query.isError} isFetching={query.isFetching} dataUpdatedAt={query.dataUpdatedAt} />
      <TabNav active={tab} onChange={setTab} />

      {tab === 'dashboard' && (
        <>
          {query.isError && (
            <div className="error-banner">
              서버 연결 실패 — 재시도 중... ({query.error instanceof Error ? query.error.message : String(query.error)})
            </div>
          )}

          <div className="grid">
            <OperationCard status={view?.trading.status} />
            <KillSwitchCard killSwitchEngaged={view?.trading.killSwitchEngaged} />
            <PerformanceCard trading={view?.trading} />
            <SlippageCard slippage={view?.slippage} />
            <SystemCard system={view?.system} />
            <TestSignalCard />
            <PositionsCard positions={view?.positions} />
            <EventFeedCard events={view?.recentEvents} />
          </div>
        </>
      )}

      {tab === 'orders' && <OrdersPage />}
      {tab === 'performance' && <PerformancePage />}
      {tab === 'decisions' && <DecisionsPage />}
      {tab === 'ipo' && <IpoPage />}
    </>
  );
}

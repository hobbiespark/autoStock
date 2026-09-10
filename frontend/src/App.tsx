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

export default function App() {
  // 대시보드는 /api/dashboard 하나만 폴링한다(CQRS Lite, ARCHITECTURE.md 10절).
  // 단일 쿼리 키 ['dashboard']로 모든 카드가 같은 데이터를 공유한다.
  const query = useQuery({
    queryKey: ['dashboard'],
    queryFn: fetchDashboard,
    refetchInterval: 2000,
  });

  const view = query.data;

  return (
    <>
      <Header status={view?.trading.status} isError={query.isError} isFetching={query.isFetching} dataUpdatedAt={query.dataUpdatedAt} />

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
  );
}

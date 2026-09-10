// 경량 탭 내비게이션 — 화면 3개(대시보드/주문 이력/성과)라 라우터 라이브러리 없이
// useState로 처리한다(PLAN.md ADR-10 확장표: "화면 3개 시점" 기준을 그대로 충족하지만,
// 번들 비대화를 피하려 react-router는 아직 도입하지 않는다 — 추가 화면이 더 늘면 재검토).
export type Tab = 'dashboard' | 'orders' | 'performance';

const TAB_LABELS: Record<Tab, string> = {
  dashboard: '대시보드',
  orders: '주문 이력',
  performance: '성과',
};

interface Props {
  active: Tab;
  onChange: (tab: Tab) => void;
}

export default function TabNav({ active, onChange }: Props) {
  return (
    <nav className="tab-nav">
      {(Object.keys(TAB_LABELS) as Tab[]).map((tab) => (
        <button
          key={tab}
          className={`tab-nav-btn ${active === tab ? 'active' : ''}`}
          onClick={() => onChange(tab)}
        >
          {TAB_LABELS[tab]}
        </button>
      ))}
    </nav>
  );
}

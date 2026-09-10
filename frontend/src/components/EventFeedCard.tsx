import { useEffect, useRef, useState } from 'react';
import type { DashboardEvent, EventType } from '../types';

interface Props {
  events?: DashboardEvent[];
}

type FilterTab = 'ALL' | 'SIGNAL' | 'ORDER' | 'FILL';
const TABS: FilterTab[] = ['ALL', 'SIGNAL', 'ORDER', 'FILL'];

function eventKey(e: DashboardEvent): string {
  return `${e.at}|${e.type}|${e.summary}`;
}

export default function EventFeedCard({ events }: Props) {
  const [filter, setFilter] = useState<FilterTab>('ALL');
  const [paused, setPaused] = useState(false);
  // 일시정지 중에도 폴링(쿼리) 자체는 계속되지만, 화면에 보이는 목록은 얼려둔다.
  const [frozenEvents, setFrozenEvents] = useState<DashboardEvent[] | undefined>(events);
  const [highlighted, setHighlighted] = useState<Set<string>>(new Set());
  const knownKeys = useRef<Set<string>>(new Set());
  const timers = useRef<number[]>([]);

  useEffect(() => {
    if (paused || events === undefined) return;

    const newKeys: string[] = [];
    for (const e of events) {
      const key = eventKey(e);
      if (!knownKeys.current.has(key)) {
        newKeys.push(key);
      }
    }
    knownKeys.current = new Set(events.map(eventKey));
    setFrozenEvents(events);

    if (newKeys.length > 0) {
      setHighlighted((prev) => {
        const next = new Set(prev);
        newKeys.forEach((k) => next.add(k));
        return next;
      });
      const timer = window.setTimeout(() => {
        setHighlighted((prev) => {
          const next = new Set(prev);
          newKeys.forEach((k) => next.delete(k));
          return next;
        });
      }, 2000);
      timers.current.push(timer);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [events, paused]);

  useEffect(() => {
    return () => {
      timers.current.forEach((t) => window.clearTimeout(t));
    };
  }, []);

  const filtered = (frozenEvents ?? []).filter(
    (e) => filter === 'ALL' || (e.type as EventType) === filter,
  );

  return (
    <div className="card">
      <h2>최근 이벤트</h2>
      <div className="feed-controls">
        <div className="tabs">
          {TABS.map((tab) => (
            <span
              key={tab}
              className={`tab ${filter === tab ? 'active' : ''}`}
              onClick={() => setFilter(tab)}
            >
              {tab === 'ALL' ? '전체' : tab}
            </span>
          ))}
        </div>
        <button className="secondary" onClick={() => setPaused((p) => !p)}>
          {paused ? '재개' : '일시정지'}
        </button>
      </div>
      <div className="feed">
        {frozenEvents === undefined ? (
          '-'
        ) : filtered.length === 0 ? (
          '이벤트 없음'
        ) : (
          filtered.map((e) => {
            const key = eventKey(e);
            return (
              <div key={key} className={`feed-item ${highlighted.has(key) ? 'highlight' : ''}`}>
                <span className={`tag ${e.type}`}>{e.type}</span>
                <span>{e.summary}</span>
                <span className="feed-time">{new Date(e.at).toLocaleTimeString()}</span>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
}

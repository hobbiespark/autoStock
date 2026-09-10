import type { IpoDeal } from '../types';

function todayKst(): Date {
  const s = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' }).format(new Date());
  return new Date(`${s}T00:00:00`);
}

function daysBetween(from: Date, to: Date): number {
  return Math.round((to.getTime() - from.getTime()) / (1000 * 60 * 60 * 24));
}

/**
 * D-day 뱃지(요구사항, FE-3): 청약 시작까지 남은 일수 / 청약 중 / 상장 D-day.
 * status(백엔드 IpoStatus)와 날짜 필드를 조합해 사람이 읽을 라벨을 만든다 — 서버가 이미
 * status를 계산해 주지만(IpoSyncScheduler), 화면에는 "D-3"처럼 더 구체적인 문구가 필요하다.
 */
export default function IpoDdayBadge({ deal }: { deal: IpoDeal }) {
  const today = todayKst();

  if (deal.status === 'SUBSCRIBING') {
    return <span className="ipo-dday-badge ipo-dday-active">청약 중</span>;
  }
  if (deal.status === 'LISTED') {
    return <span className="ipo-dday-badge ipo-dday-listed">상장</span>;
  }
  if (deal.status === 'UPCOMING' && deal.subscriptionStart) {
    const d = daysBetween(today, new Date(`${deal.subscriptionStart}T00:00:00`));
    return <span className="ipo-dday-badge ipo-dday-upcoming">{d > 0 ? `D-${d}` : 'D-day'}</span>;
  }
  if (deal.status === 'PASSED') {
    return <span className="ipo-dday-badge ipo-dday-passed">청약 종료</span>;
  }
  return <span className="ipo-dday-badge ipo-dday-passed">일정 미확정</span>;
}

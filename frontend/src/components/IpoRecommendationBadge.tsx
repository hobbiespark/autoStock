import type { IpoRecommendation } from '../types';

// 권고 뱃지 색상(요구사항, FE-3): RECOMMEND=초록, SKIP=회색, PENDING=주황.
function badgeClass(recommendation: IpoRecommendation): string {
  if (recommendation === 'RECOMMEND') return 'ipo-badge ipo-badge-recommend';
  if (recommendation === 'SKIP') return 'ipo-badge ipo-badge-skip';
  return 'ipo-badge ipo-badge-pending';
}

const LABELS: Record<IpoRecommendation, string> = {
  RECOMMEND: '청약 권고',
  SKIP: '비권고',
  PENDING: '지표 대기',
};

export default function IpoRecommendationBadge({ recommendation }: { recommendation: IpoRecommendation }) {
  return <span className={badgeClass(recommendation)}>{LABELS[recommendation]}</span>;
}

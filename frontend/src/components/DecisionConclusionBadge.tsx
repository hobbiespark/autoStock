import type { DecisionConclusion } from '../types';

// 결론 뱃지 색상 규칙(요구사항, FE-6): BUY=초록, REJECTED=빨강, SKIP=회색(중립),
// HOLD=중립, SELL은 청산이라 BUY와 구분되는 색(주황 계열)으로 둔다.
function badgeClass(conclusion: DecisionConclusion): string {
  if (conclusion === 'BUY') return 'decision-badge decision-badge-buy';
  if (conclusion === 'SELL') return 'decision-badge decision-badge-sell';
  if (conclusion === 'REJECTED') return 'decision-badge decision-badge-rejected';
  if (conclusion === 'SKIP') return 'decision-badge decision-badge-skip';
  return 'decision-badge decision-badge-hold'; // HOLD 및 그 외
}

const LABELS: Record<string, string> = {
  BUY: '매수',
  SELL: '매도',
  HOLD: '보유유지',
  SKIP: '보류',
  REJECTED: '거부',
};

export default function DecisionConclusionBadge({ conclusion }: { conclusion: DecisionConclusion }) {
  return <span className={badgeClass(conclusion)}>{LABELS[conclusion] ?? conclusion}</span>;
}

import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchIpoDeals, postIpoMetrics, postIpoRecord } from '../api';
import IpoDdayBadge from '../components/IpoDdayBadge';
import IpoRecommendationBadge from '../components/IpoRecommendationBadge';
import type { IpoDeal, IpoStatus } from '../types';

const STATUS_ALL = '전체' as const;
const STATUS_TABS: IpoStatus[] = ['UPCOMING', 'SUBSCRIBING', 'LISTED', 'PASSED'];
const STATUS_LABELS: Record<string, string> = {
  UPCOMING: '청약 예정',
  SUBSCRIBING: '청약 중',
  LISTED: '상장 완료',
  PASSED: '청약 종료',
};

function formatPrice(v: string | number | null): string {
  if (v === null || v === undefined) return '-';
  const n = typeof v === 'number' ? v : Number(v);
  return Number.isFinite(n) ? `${n.toLocaleString()}원` : String(v);
}

function formatBand(deal: IpoDeal): string {
  if (deal.offerPriceLow != null && deal.offerPriceHigh != null) {
    return `${formatPrice(deal.offerPriceLow)} ~ ${formatPrice(deal.offerPriceHigh)}`;
  }
  if (deal.offerPriceConfirmed != null) {
    return `${formatPrice(deal.offerPriceConfirmed)} (확정)`;
  }
  return '미확정';
}

/**
 * 공모주 화면(FE-3, PLAN.md ADR-9 트랙 E2) — 청약 캘린더(D-day)·딜 상세·권고 근거·내 기록
 * 입력. 청약 "실행"은 이 화면의 범위가 아니다(안내 문구로 명시) — API 미지원 확정(ADR-9),
 * 영웅문S#에서 수동으로 한다.
 */
export default function IpoPage() {
  const [statusTab, setStatusTab] = useState<string>(STATUS_ALL);
  const [expandedId, setExpandedId] = useState<number | null>(null);

  const query = useQuery({
    queryKey: ['ipo', statusTab],
    queryFn: () => fetchIpoDeals(statusTab === STATUS_ALL ? undefined : statusTab),
    refetchOnWindowFocus: false,
  });

  const deals = query.data ?? [];

  return (
    <div className="card" style={{ maxWidth: 1100 }}>
      <div className="page-header-row">
        <h2>공모주</h2>
        <button className="secondary" onClick={() => query.refetch()} disabled={query.isFetching}>
          {query.isFetching ? '새로고침 중...' : '새로고침'}
        </button>
      </div>

      <p className="ipo-disclaimer">
        청약 실행은 영웅문S#에서 수동 — 이 화면은 판단·기록용입니다(키움 REST API가 청약 TR을
        제공하지 않습니다, PLAN.md ADR-9).
      </p>

      <nav className="horizon-sub-nav">
        <button
          className={`horizon-sub-nav-btn ${statusTab === STATUS_ALL ? 'active' : ''}`}
          onClick={() => setStatusTab(STATUS_ALL)}
        >
          전체
        </button>
        {STATUS_TABS.map((s) => (
          <button
            key={s}
            className={`horizon-sub-nav-btn ${statusTab === s ? 'active' : ''}`}
            onClick={() => setStatusTab(s)}
          >
            {STATUS_LABELS[s]}
          </button>
        ))}
      </nav>

      {query.isError && (
        <div className="error-banner">
          공모주 목록 조회 실패 — {query.error instanceof Error ? query.error.message : String(query.error)}
        </div>
      )}

      {query.isLoading ? (
        <p className="muted">조회 중...</p>
      ) : deals.length === 0 ? (
        <p className="muted">해당 조건의 공모주 딜이 없습니다(DART 수집 배치는 평일 08:20 KST 실행).</p>
      ) : (
        <div className="decision-list">
          {deals.map((deal) => (
            <IpoRow
              key={deal.id}
              deal={deal}
              expanded={expandedId === deal.id}
              onToggle={() => setExpandedId(expandedId === deal.id ? null : deal.id)}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function IpoRow({ deal, expanded, onToggle }: { deal: IpoDeal; expanded: boolean; onToggle: () => void }) {
  return (
    <div className="decision-row ipo-row">
      <div className="decision-row-head" onClick={onToggle} style={{ cursor: 'pointer' }}>
        <IpoDdayBadge deal={deal} />
        <span className="decision-row-symbol">{deal.corpName}</span>
        <IpoRecommendationBadge recommendation={deal.recommendation} />
        <span className="muted">{formatBand(deal)}</span>
        <span className="muted">{deal.leadManager ?? '주관사 미확정'}</span>
        <span className="muted" style={{ marginLeft: 'auto' }}>
          {expanded ? '접기 ▲' : '상세 ▼'}
        </span>
      </div>
      <p className="decision-row-reason">{deal.recommendReason}</p>

      {expanded && <IpoDetail deal={deal} />}
    </div>
  );
}

function IpoDetail({ deal }: { deal: IpoDeal }) {
  return (
    <div className="ipo-detail">
      <div className="ipo-timeline">
        <TimelineItem label="청약기간" value={deal.subscriptionStart && deal.subscriptionEnd
          ? `${deal.subscriptionStart} ~ ${deal.subscriptionEnd}` : '미확정'} />
        <TimelineItem label="환불일" value={deal.refundDate ?? '미확정'} />
        <TimelineItem label="상장일" value={deal.listingDate ?? '미확정(수동 입력 필요)'} />
      </div>

      <MetricsSection deal={deal} />
      <RecordSection deal={deal} />
    </div>
  );
}

function TimelineItem({ label, value }: { label: string; value: string }) {
  return (
    <div className="ipo-timeline-item">
      <span className="muted">{label}</span>
      <span>{value}</span>
    </div>
  );
}

function MetricsSection({ deal }: { deal: IpoDeal }) {
  const [competitionRate, setCompetitionRate] = useState(String(deal.institutionalCompetitionRate ?? ''));
  const [lockupRate, setLockupRate] = useState(String(deal.lockupCommitRate ?? ''));
  const queryClient = useQueryClient();

  const mutation = useMutation({
    mutationFn: () =>
      postIpoMetrics(deal.id, {
        institutionalCompetitionRate: Number(competitionRate),
        lockupCommitRate: Number(lockupRate),
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['ipo'] }),
  });

  const hasMetrics = deal.institutionalCompetitionRate != null && deal.lockupCommitRate != null;

  return (
    <div className="ipo-section">
      <h3 className="chart-title">지표 (기관경쟁률·의무보유확약률)</h3>
      {hasMetrics && !mutation.isPending && (
        <p className="muted">
          기관경쟁률 {String(deal.institutionalCompetitionRate)}:1 · 확약률{' '}
          {String(deal.lockupCommitRate)}
        </p>
      )}
      <p className="muted">DART가 자동 제공하지 않는 지표입니다 — 수동 입력하면 즉시 권고가 재평가됩니다.</p>
      <div className="row">
        <input
          value={competitionRate}
          onChange={(e) => setCompetitionRate(e.target.value)}
          placeholder="기관경쟁률 (예: 600)"
        />
        <input
          value={lockupRate}
          onChange={(e) => setLockupRate(e.target.value)}
          placeholder="확약률 0~1 (예: 0.25)"
        />
        <button
          disabled={mutation.isPending || competitionRate === '' || lockupRate === ''}
          onClick={() => mutation.mutate()}
        >
          {mutation.isPending ? '저장 중...' : '지표 저장'}
        </button>
      </div>
      {mutation.isError && (
        <p className="muted" style={{ color: 'var(--danger)' }}>
          저장 실패 — {mutation.error instanceof Error ? mutation.error.message : String(mutation.error)}
        </p>
      )}
    </div>
  );
}

function RecordSection({ deal }: { deal: IpoDeal }) {
  const [appliedQty, setAppliedQty] = useState(deal.appliedQty != null ? String(deal.appliedQty) : '');
  const [deposit, setDeposit] = useState(deal.deposit != null ? String(deal.deposit) : '');
  const [allocatedQty, setAllocatedQty] = useState(deal.allocatedQty != null ? String(deal.allocatedQty) : '');
  const [sellPrice, setSellPrice] = useState(deal.sellPrice != null ? String(deal.sellPrice) : '');
  const [sellDate, setSellDate] = useState(deal.sellDate ?? '');
  const [memo, setMemo] = useState(deal.memo ?? '');
  const queryClient = useQueryClient();

  const mutation = useMutation({
    mutationFn: () =>
      postIpoRecord(deal.id, {
        appliedQty: appliedQty === '' ? null : Number(appliedQty),
        deposit: deposit === '' ? null : Number(deposit),
        allocatedQty: allocatedQty === '' ? null : Number(allocatedQty),
        sellPrice: sellPrice === '' ? null : Number(sellPrice),
        sellDate: sellDate === '' ? null : sellDate,
        memo: memo === '' ? null : memo,
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['ipo'] }),
  });

  return (
    <div className="ipo-section">
      <h3 className="chart-title">내 청약 기록</h3>
      <div className="row">
        <input value={appliedQty} onChange={(e) => setAppliedQty(e.target.value)} placeholder="청약 수량" />
        <input value={deposit} onChange={(e) => setDeposit(e.target.value)} placeholder="증거금" />
        <input value={allocatedQty} onChange={(e) => setAllocatedQty(e.target.value)} placeholder="배정 수량" />
      </div>
      <div className="row">
        <input value={sellPrice} onChange={(e) => setSellPrice(e.target.value)} placeholder="매도가" />
        <input type="date" value={sellDate} onChange={(e) => setSellDate(e.target.value)} />
        <input value={memo} onChange={(e) => setMemo(e.target.value)} placeholder="메모" />
      </div>
      <button disabled={mutation.isPending} onClick={() => mutation.mutate()}>
        {mutation.isPending ? '저장 중...' : '기록 저장'}
      </button>
      {mutation.isError && (
        <p className="muted" style={{ color: 'var(--danger)' }}>
          저장 실패 — {mutation.error instanceof Error ? mutation.error.message : String(mutation.error)}
        </p>
      )}
    </div>
  );
}

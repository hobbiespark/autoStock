package com.autostock.monitor.view;

import com.autostock.monitor.EventFeed;
import com.autostock.monitor.SlippageTracker;

import java.util.List;

/**
 * 대시보드 전체 화면을 한 번에 채우는 View DTO — {@code GET /api/dashboard} 하나의 응답
 * (ARCHITECTURE.md 10절 "대시보드는 GET /api/dashboard 하나로 조합").
 *
 * <p>{@code recentEvents}는 별도의 OrderViewItem류 레코드를 새로 만들지 않고
 * {@link EventFeed.FeedItem}을 그대로 재사용한다 — 이미 "종류+요약+시각"이라는
 * 화면에 필요한 형태 그대로이기 때문이다(불필요한 분산 패턴 금지, ARCHITECTURE.md 규칙 20).
 *
 * @param positions    보유 포지션 목록
 * @param recentEvents 최근 이벤트 피드(최신순 최대 100건)
 * @param trading      매매 상태(운영 상태기계 + 오늘 실적)
 * @param system       시스템(설정) 상태
 * @param slippage     오늘 슬리피지 요약(결정가 대비 체결가, bps 양수=불리 — 트랙 C2)
 */
public record DashboardView(
        List<PositionView> positions,
        List<EventFeed.FeedItem> recentEvents,
        TradingStatusView trading,
        SystemStatusView system,
        SlippageTracker.SlippageSummary slippage
) {
}

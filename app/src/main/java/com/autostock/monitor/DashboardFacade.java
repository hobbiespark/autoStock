package com.autostock.monitor;

import com.autostock.trading.TradingProperties;
import com.autostock.monitor.view.DashboardView;
import com.autostock.monitor.view.PositionView;
import com.autostock.monitor.view.SystemStatusView;
import com.autostock.monitor.view.TradingStatusView;
import com.autostock.risk.DailyLimitTracker;
import com.autostock.risk.DailyPnlTracker;
import com.autostock.risk.KillSwitch;
import com.autostock.portfolio.PositionBook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 대시보드 Facade — 여러 모듈의 조회를 View DTO로 <b>조합만</b> 한다(CQRS Lite,
 * ARCHITECTURE.md 10절). 업무 규칙(주문 가능 여부 판단, 손익 재계산 등)은 여기 두지 않는다 —
 * 그건 이미 risk/trading/portfolio/strategy 모듈이 가진 책임이고, 이 클래스가 하는 일은 그
 * 결과값들을 "화면이 원하는 모양"으로 옮겨 담는 것뿐이다.
 *
 * <p>이 클래스가 참조하는 것: portfolio.PositionBook, risk.KillSwitch/DailyLimitTracker/
 * DailyPnlTracker(기존 DashboardController가 이미 참조하던 것과 동일 — DailyReportScheduler도
 * 같은 패턴), trading.TradingProperties(모드 표시용, ADR-6 재편으로 execution → trading 이동),
 * 그리고 monitor 자신의 EventFeed/TradingSystemManager.
 * strategy 모듈 타입은 참조하지 않는다 — {@link com.autostock.monitor.view.SystemStatusView}
 * Javadoc에 설명된 순환(cycle) 회피 때문에 {@code strategy.c3.enabled}는 {@code @Value}로
 * 직접 읽는다.
 */
@Component
public class DashboardFacade {

    private final PositionBook positionBook;
    private final EventFeed eventFeed;
    private final KillSwitch killSwitch;
    private final DailyLimitTracker dailyLimitTracker;
    private final DailyPnlTracker dailyPnlTracker;
    private final TradingSystemManager tradingSystemManager;
    private final TradingProperties tradingProperties;
    private final boolean c3Enabled;
    private final boolean wsEnabled;

    public DashboardFacade(PositionBook positionBook,
                           EventFeed eventFeed,
                           KillSwitch killSwitch,
                           DailyLimitTracker dailyLimitTracker,
                           DailyPnlTracker dailyPnlTracker,
                           TradingSystemManager tradingSystemManager,
                           TradingProperties tradingProperties,
                           @Value("${strategy.c3.enabled:false}") boolean c3Enabled,
                           @Value("${autostock.ws.enabled:false}") boolean wsEnabled) {
        this.positionBook = positionBook;
        this.eventFeed = eventFeed;
        this.killSwitch = killSwitch;
        this.dailyLimitTracker = dailyLimitTracker;
        this.dailyPnlTracker = dailyPnlTracker;
        this.tradingSystemManager = tradingSystemManager;
        this.tradingProperties = tradingProperties;
        this.c3Enabled = c3Enabled;
        this.wsEnabled = wsEnabled;
    }

    /** 대시보드 전체 조합 — GET /api/dashboard 하나가 부르는 진입점. */
    public DashboardView dashboard() {
        return new DashboardView(positions(), recentEvents(), trading(), system());
    }

    /** 포지션 View 목록. */
    public List<PositionView> positions() {
        return positionBook.snapshot().entrySet().stream()
                .map(e -> new PositionView(e.getKey(), e.getValue().quantity(), e.getValue().avgPrice()))
                .toList();
    }

    /** 최근 이벤트 피드 — EventFeed.FeedItem 그대로(DashboardView Javadoc 참고). */
    public List<EventFeed.FeedItem> recentEvents() {
        return eventFeed.recent();
    }

    /** 매매 상태 View 조합. */
    public TradingStatusView trading() {
        return new TradingStatusView(
                tradingSystemManager.status(),
                killSwitch.isEngaged(),
                dailyLimitTracker.todayOrderCount(),
                dailyPnlTracker.todayRealizedPnl());
    }

    /** 시스템(설정) 상태 View 조합. */
    public SystemStatusView system() {
        return new SystemStatusView(
                tradingProperties.mode().name(),
                c3Enabled,
                wsEnabled);
    }
}

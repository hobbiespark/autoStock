package com.autostock.monitor;

import com.autostock.common.util.StockCode;
import com.autostock.common.util.StockNames;
import com.autostock.execution.BrokerHolding;
import com.autostock.trading.TradingProperties;
import com.autostock.monitor.view.AccountView;
import com.autostock.monitor.view.DailyLossLimitView;
import com.autostock.monitor.view.DashboardView;
import com.autostock.monitor.view.PositionView;
import com.autostock.monitor.view.SystemStatusView;
import com.autostock.monitor.view.TradingStatusView;
import com.autostock.risk.DailyLimitTracker;
import com.autostock.risk.DailyPnlTracker;
import com.autostock.risk.DisclosureBlacklist;
import com.autostock.risk.KillSwitch;
import com.autostock.risk.MacroGuard;
import com.autostock.portfolio.PositionBook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 대시보드 Facade — 여러 모듈의 조회를 View DTO로 <b>조합만</b> 한다(CQRS Lite,
 * ARCHITECTURE.md 10절). 업무 규칙(주문 가능 여부 판단, 손익 재계산 등)은 여기 두지 않는다 —
 * 그건 이미 risk/trading/portfolio/strategy 모듈이 가진 책임이고, 이 클래스가 하는 일은 그
 * 결과값들을 "화면이 원하는 모양"으로 옮겨 담는 것뿐이다.
 *
 * <p>이 클래스가 참조하는 것: portfolio.PositionBook, risk.KillSwitch/DailyLimitTracker/
 * DailyPnlTracker(기존 DashboardController가 이미 참조하던 것과 동일 — DailyReportScheduler도
 * 같은 패턴), risk.MacroGuard(보수 모드 표시, PLAN 5절 macro-intel), trading.TradingProperties
 * (모드 표시용, ADR-6 재편으로 execution → trading 이동), 그리고 monitor 자신의
 * EventFeed/TradingSystemManager.
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
    private final MacroGuard macroGuard;
    private final DisclosureBlacklist disclosureBlacklist;
    private final TradingSystemManager tradingSystemManager;
    private final TradingProperties tradingProperties;
    private final SlippageTracker slippageTracker;
    private final boolean c3Enabled;
    private final boolean wsEnabled;
    private final long manualOrderMaxKrw;
    /** 계좌 잔고(2026-10-23) — 브로커 조회는 이 서비스가 주기를 지켜서 한다. */
    private final AccountSummaryService accountSummary;
    /** 키움 접속 서버(2026-10-23) — kiwoom.rest-base-url에서 MOCK/REAL을 가린다. */
    private final String brokerServer;

    public DashboardFacade(PositionBook positionBook,
                           EventFeed eventFeed,
                           KillSwitch killSwitch,
                           DailyLimitTracker dailyLimitTracker,
                           DailyPnlTracker dailyPnlTracker,
                           MacroGuard macroGuard,
                           DisclosureBlacklist disclosureBlacklist,
                           TradingSystemManager tradingSystemManager,
                           TradingProperties tradingProperties,
                           SlippageTracker slippageTracker,
                           @Value("${strategy.c3.enabled:false}") boolean c3Enabled,
                           @Value("${autostock.ws.enabled:false}") boolean wsEnabled,
                           @Value("${risk.manual-order-max-krw:1000000}") long manualOrderMaxKrw,
                           AccountSummaryService accountSummary,
                           @Value("${kiwoom.rest-base-url:}") String kiwoomRestBaseUrl) {
        this.positionBook = positionBook;
        this.eventFeed = eventFeed;
        this.killSwitch = killSwitch;
        this.dailyLimitTracker = dailyLimitTracker;
        this.dailyPnlTracker = dailyPnlTracker;
        this.macroGuard = macroGuard;
        this.disclosureBlacklist = disclosureBlacklist;
        this.tradingSystemManager = tradingSystemManager;
        this.tradingProperties = tradingProperties;
        this.slippageTracker = slippageTracker;
        this.c3Enabled = c3Enabled;
        this.wsEnabled = wsEnabled;
        this.manualOrderMaxKrw = manualOrderMaxKrw;
        this.accountSummary = accountSummary;
        this.brokerServer = brokerServerOf(kiwoomRestBaseUrl);
    }

    /** kiwoom.rest-base-url → MOCK(mockapi.kiwoom.com)·REAL(api.kiwoom.com)·UNKNOWN. */
    static String brokerServerOf(String restBaseUrl) {
        String url = restBaseUrl == null ? "" : restBaseUrl.toLowerCase(Locale.ROOT);
        if (url.contains("mockapi.kiwoom.com")) {
            return "MOCK";
        }
        if (url.contains("api.kiwoom.com")) {
            return "REAL";
        }
        return "UNKNOWN";
    }

    /** 대시보드 전체 조합 — GET /api/dashboard 하나가 부르는 진입점. 잔고를 먼저 읽어 보유 종목 현재가에 쓴다. */
    public DashboardView dashboard() {
        AccountView account = account();
        BigDecimal accountEquity = account != null && account.available() ? account.totalAsset() : null;
        return new DashboardView(positions(), recentEvents(), trading(accountEquity), system(),
                slippageTracker.todaySummary(), account);
    }

    /** 계좌 잔고 View(2026-10-23) — 모르면 null. */
    public AccountView account() {
        return accountSummary == null ? null : accountSummary.account();
    }

    /**
     * 포지션 View 목록. 현재가는 마지막으로 읽은 키움 잔고에서 가져오고(조회하지 않는다), 평가손익·수익률은 비용 전 가격 기준이다
     * (2026-10-23). 수량·평단은 앱 장부(체결 즉시 반영) 그대로다.
     */
    public List<PositionView> positions() {
        Map<StockCode, BrokerHolding> holdings = accountSummary == null ? Map.of() : accountSummary.latestHoldings();
        return positionBook.snapshot().entrySet().stream()
                .map(e -> {
                    long quantity = e.getValue().quantity();
                    BigDecimal avg = e.getValue().avgPrice();
                    BrokerHolding holding = holdings == null ? null : holdings.get(e.getKey());
                    BigDecimal current = holding == null || holding.currentPrice() == null
                            ? null : holding.currentPrice().value();
                    BigDecimal pnl = null;
                    BigDecimal returnPct = null;
                    if (current != null && avg != null && avg.signum() > 0) {
                        pnl = current.subtract(avg).multiply(BigDecimal.valueOf(quantity)).setScale(0, RoundingMode.HALF_UP);
                        returnPct = current.subtract(avg).multiply(BigDecimal.valueOf(100)).divide(avg, 2, RoundingMode.HALF_UP);
                    }
                    return new PositionView(e.getKey().value(), StockNames.nameOf(e.getKey()).orElse(null), quantity, avg,
                            current, pnl, returnPct);
                })
                .toList();
    }

    /** 최근 이벤트 피드 — EventFeed.FeedItem 그대로(DashboardView Javadoc 참고). */
    public List<EventFeed.FeedItem> recentEvents() {
        return eventFeed.recent();
    }

    /** 매매 상태 View 조합. */
    public TradingStatusView trading() {
        return trading(null);
    }

    /**
     * 매매 상태 View 조합 — {@code accountEquity}는 일 손실 한도 기준을 아직 모를 때(리스크 쪽이 계좌 평가액을 한 번도 읽지 않은
     * 기동 직후) 대신 쓸 계좌 총자산(대시보드가 읽은 잔고). 모르면 null.
     */
    public TradingStatusView trading(BigDecimal accountEquity) {
        return new TradingStatusView(
                tradingSystemManager.status(),
                killSwitch.isEngaged(),
                dailyLimitTracker.todayOrderCount(),
                dailyPnlTracker.todayRealizedPnl(),
                macroGuard.isConservativeMode(),
                disclosureBlacklist.activeCount(),
                dailyLossLimit(accountEquity));
    }

    /** 일 손실 한도의 지금 값(2026-10-23) — 모르면 null. 브로커를 조회하지 않는다(DailyPnlTracker.limitStatus). */
    private DailyLossLimitView dailyLossLimit(BigDecimal accountEquity) {
        DailyPnlTracker.LimitStatus s = dailyPnlTracker.limitStatus(accountEquity);
        if (s == null) {
            return null;
        }
        return new DailyLossLimitView(s.lossPct(), s.base(), s.threshold(), s.pnlForLimit(), s.capitalLimited(),
                s.capitalLimit(), s.peakHeld());
    }

    /** 시스템(설정) 상태 View 조합. */
    public SystemStatusView system() {
        return new SystemStatusView(
                tradingProperties.mode().name(),
                c3Enabled,
                wsEnabled,
                manualOrderMaxKrw,
                brokerServer);
    }
}

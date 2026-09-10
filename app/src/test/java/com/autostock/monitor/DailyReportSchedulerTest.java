package com.autostock.monitor;

import com.autostock.common.event.Fill;
import com.autostock.common.event.Side;
import com.autostock.macrointel.MacroIntelProperties;
import com.autostock.risk.DailyLimitTracker;
import com.autostock.risk.DailyPnlTracker;
import com.autostock.risk.KillSwitch;
import com.autostock.risk.MacroGuard;
import com.autostock.risk.PaperEquitySource;
import com.autostock.portfolio.PositionBook;
import com.autostock.risk.RiskProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DailyReportScheduler — 리포트 본문에 포지션/킬스위치/주문 수/실현손익이 반영되는지 검증한다.
 * 실제 스케줄 트리거(cron)는 검증하지 않는다(Spring 스케줄러 자체는 프레임워크 책임).
 */
class DailyReportSchedulerTest {

    /** 스냅샷 upsert 호출 기록 — DB 없이 DailyPerformanceRecorder 계약만 검증한다. */
    private record SnapshotCall(LocalDate tradeDate, BigDecimal realizedPnl, int orderCount, int fillCount,
                                double avgSlippageBps, double maxSlippageBps,
                                boolean conservativeMode, boolean killSwitchEngaged) {
    }

    private final List<Object> notices = new ArrayList<>();
    private final Notifier fakeNotifier = (level, message) -> notices.add(level + ":" + message);
    private final List<SnapshotCall> snapshotCalls = new ArrayList<>();
    private final DailyPerformanceRecorder fakeRecorder = (tradeDate, realizedPnl, orderCount, fillCount,
                                                            avgSlippageBps, maxSlippageBps,
                                                            conservativeMode, killSwitchEngaged) ->
            snapshotCalls.add(new SnapshotCall(tradeDate, realizedPnl, orderCount, fillCount,
                    avgSlippageBps, maxSlippageBps, conservativeMode, killSwitchEngaged));
    private PositionBook positionBook;
    private KillSwitch killSwitch;
    private DailyLimitTracker dailyLimits;
    private DailyPnlTracker dailyPnl;
    private DailyReportScheduler scheduler;
    private Clock clock;

    @BeforeEach
    void setUp() {
        positionBook = new PositionBook();
        killSwitch = new KillSwitch(event -> { });
        RiskProperties properties = new RiskProperties(0.10, 5, -0.03, 0.05, -0.02, 30,
                10_000_000, 0.00015, 0.0015, false);
        dailyLimits = new DailyLimitTracker(properties);
        clock = Clock.fixed(Instant.parse("2026-08-13T02:00:00Z"), ZoneOffset.UTC);
        dailyPnl = new DailyPnlTracker(properties, new PaperEquitySource(properties), killSwitch, clock);
        // 보수 모드는 이 테스트의 관심사가 아니라 기본값(OFF)으로 둔다 — MacroGuardTest 참고.
        MacroGuard macroGuard = new MacroGuard(
                new MacroIntelProperties(false, "", "", 25.0, 35.0, 1450.0), killSwitch);
        scheduler = new DailyReportScheduler(positionBook, dailyLimits, killSwitch, dailyPnl, macroGuard,
                new SlippageTracker(java.time.Clock.systemUTC(), new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                fakeNotifier, fakeRecorder, clock);
    }

    @Test
    void 리포트는_INFO_한_건으로_발송되고_포지션과_킬스위치_상태를_포함한다() {
        positionBook.onFill(new Fill("k1", "b1", "005930", Side.BUY, 10,
                new BigDecimal("70000"), Instant.now()));

        scheduler.sendDailyReport();

        assertEquals(1, notices.size());
        String notice = (String) notices.get(0);
        assertTrue(notice.startsWith("INFO:"));
        assertTrue(notice.contains("005930"));
        assertTrue(notice.contains("정상")); // 킬스위치 미작동 상태
    }

    @Test
    void 킬스위치_작동_중이면_리포트에_반영된다() {
        killSwitch.engage("테스트");

        scheduler.sendDailyReport();

        assertTrue(((String) notices.get(0)).contains("작동 중"));
    }

    @Test
    void 리포트_발송과_함께_일별_성과_스냅샷을_1건_저장한다() {
        killSwitch.engage("테스트");

        scheduler.sendDailyReport();

        assertEquals(1, snapshotCalls.size());
        SnapshotCall call = snapshotCalls.get(0);
        assertEquals(LocalDate.of(2026, 8, 13), call.tradeDate()); // clock=2026-08-13T02:00:00Z → KST 11:00, 같은 날짜
        assertEquals(0, call.orderCount());
        assertEquals(0, call.fillCount());
        assertTrue(call.killSwitchEngaged());
    }
}

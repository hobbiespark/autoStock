package com.autostock.monitor;

import com.autostock.common.util.MarketConstants;
import com.autostock.execution.BrokerBalance;
import com.autostock.execution.BrokerPort;
import com.autostock.risk.DailyLimitTracker;
import com.autostock.risk.DailyPnlTracker;
import com.autostock.risk.KillSwitch;
import com.autostock.risk.MacroGuard;
import com.autostock.portfolio.PositionBook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 일일 성과 리포트 — 매 평일 15:50(KST) 장 마감(15:30) 직후 오늘 하루 요약을 알림으로 보낸다.
 *
 * <p>포함 내용: 현재 포지션 스냅샷(PositionBook), 오늘 주문 수(DailyLimitTracker),
 * 킬스위치 상태, 오늘 실현손익(DailyPnlTracker — PLAN 8절 일 손실 한도 안전장치와 같은
 * 데이터 소스를 그대로 보여준다), 거시 국면 보수 모드 여부(MacroGuard, PLAN 5절 macro-intel).
 * 계좌 평가액은 {@link BrokerPort#balance()}(kt00018)에서 직접 읽어 추정예탁자산(총자산)·
 * 총평가·총평가손익을 붙인다(2026-09-23 — 잔고 연동은 PositionRestorer/BrokerEquitySource로 이미
 * 되어 있었고 리포트만 옛 TODO 문자열을 찍고 있었다). 조회 실패 시 리포트 자체는 계속 보낸다.
 *
 * <p><b>일별 성과 스냅샷 저장(FE-2, PLAN.md ADR-10 확장표)</b>: 리포트 발송과 같은 스케줄
 * 실행에서 {@link DailyPerformanceRecorder}로 그날의 집계를 upsert한다 — 리포트 본문과
 * 같은 데이터 소스(DailyLimitTracker/DailyPnlTracker/SlippageTracker/MacroGuard/KillSwitch)를
 * 그대로 재사용하므로 두 값이 어긋날 일이 없다.
 */
@Component
public class DailyReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyReportScheduler.class);

    private final PositionBook positionBook;
    private final BrokerPort brokerPort;
    private final DailyLimitTracker dailyLimits;
    private final KillSwitch killSwitch;
    private final DailyPnlTracker dailyPnl;
    private final MacroGuard macroGuard;
    private final SlippageTracker slippageTracker;
    private final Notifier notifier;
    private final DailyPerformanceRecorder performanceRecorder;
    private final Clock clock;

    public DailyReportScheduler(PositionBook positionBook,
                                BrokerPort brokerPort,
                                DailyLimitTracker dailyLimits,
                                KillSwitch killSwitch,
                                DailyPnlTracker dailyPnl,
                                MacroGuard macroGuard,
                                SlippageTracker slippageTracker,
                                Notifier notifier,
                                DailyPerformanceRecorder performanceRecorder,
                                Clock clock) {
        this.positionBook = positionBook;
        this.brokerPort = brokerPort;
        this.dailyLimits = dailyLimits;
        this.killSwitch = killSwitch;
        this.dailyPnl = dailyPnl;
        this.macroGuard = macroGuard;
        this.slippageTracker = slippageTracker;
        this.notifier = notifier;
        this.performanceRecorder = performanceRecorder;
        this.clock = clock;
    }

    /** 평일 15:50 KST 1회 실행. */
    @Scheduled(cron = "0 50 15 * * MON-FRI", zone = "Asia/Seoul")
    public void sendDailyReport() {
        notifier.notify(NoticeLevel.INFO, buildReport());
        saveDailyPerformanceSnapshot();
    }

    /** 리포트와 같은 집계값으로 그날의 성과 스냅샷을 upsert한다(FE-2). */
    private void saveDailyPerformanceSnapshot() {
        LocalDate tradeDate = LocalDate.now(clock.withZone(MarketConstants.KST));
        var slip = slippageTracker.todaySummary();
        performanceRecorder.saveSnapshot(
                tradeDate,
                dailyPnl.todayRealizedPnl(),
                dailyLimits.todayOrderCount(),
                (int) slip.fills(),
                slip.avgBps(),
                slip.maxBps(),
                macroGuard.isConservativeMode(),
                killSwitch.isEngaged());
    }

    private String buildReport() {
        var positions = positionBook.snapshot();
        StringBuilder sb = new StringBuilder();
        sb.append("=== 일일 성과 리포트 ===\n");
        sb.append("오늘 주문 수: ").append(dailyLimits.todayOrderCount()).append('\n');
        sb.append("오늘 실현손익: ").append(dailyPnl.todayRealizedPnl()).append("원\n");
        sb.append("킬스위치: ").append(killSwitch.isEngaged() ? "작동 중" : "정상").append('\n');
        sb.append("보수 모드(거시 국면): ").append(macroGuard.isConservativeMode() ? "ON(신규 매수 금지)" : "OFF").append('\n');
        var slip = slippageTracker.todaySummary();
        sb.append("오늘 슬리피지: ").append(slip.fills() == 0
                ? "체결 없음"
                : "%d건, 평균 %.2fbps, 최대 %.2fbps(%s) — 양수=불리, 백테스트 가정 5bps 대비"
                        .formatted(slip.fills(), slip.avgBps(), slip.maxBps(), slip.maxBpsSymbol())).append('\n');
        sb.append("보유 종목 수: ").append(positions.size());
        positions.forEach((symbol, position) ->
                sb.append("\n- ").append(symbol).append(' ')
                        .append(position.quantity()).append("주 @ ").append(position.avgPrice()));
        sb.append('\n').append(balanceLine());
        return sb.toString();
    }

    private static long won(java.math.BigDecimal v) {
        return v == null ? 0L : v.longValue();
    }

    /** kt00018 잔고 한 줄 — 실패해도 리포트는 나가야 하므로 예외는 삼키고 사유만 남긴다. */
    private String balanceLine() {
        try {
            BrokerBalance b = brokerPort.balance();
            return "계좌 평가액(추정예탁자산): %,d원 / 보유 총평가 %,d원 / 총평가손익 %,d원"
                    .formatted(won(b.estimatedDepositAsset()), won(b.totalEvaluationAmount()), won(b.totalProfitLoss()));
        } catch (RuntimeException e) {
            log.warn("일일 리포트 잔고(kt00018) 조회 실패 — 평가액 생략: {}", e.getMessage());
            return "계좌 평가액: 조회 실패(" + e.getMessage() + ")";
        }
    }
}

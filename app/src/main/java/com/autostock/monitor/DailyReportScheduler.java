package com.autostock.monitor;

import com.autostock.risk.DailyLimitTracker;
import com.autostock.risk.DailyPnlTracker;
import com.autostock.risk.KillSwitch;
import com.autostock.risk.PositionBook;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 일일 성과 리포트 — 매 평일 15:50(KST) 장 마감(15:30) 직후 오늘 하루 요약을 알림으로 보낸다.
 *
 * <p>포함 내용: 현재 포지션 스냅샷(PositionBook), 오늘 주문 수(DailyLimitTracker),
 * 킬스위치 상태, 오늘 실현손익(DailyPnlTracker — PLAN 8절 일 손실 한도 안전장치와 같은
 * 데이터 소스를 그대로 보여준다). 계좌 평가액(총 자산)은 TODO — 이 리포트에는 아직 붙이지
 * 않았다(EquitySource는 risk 모듈에 있지만, 이 화면에 총자산까지 표시할지는 별도 판단 필요).
 */
@Component
public class DailyReportScheduler {

    private final PositionBook positionBook;
    private final DailyLimitTracker dailyLimits;
    private final KillSwitch killSwitch;
    private final DailyPnlTracker dailyPnl;
    private final Notifier notifier;

    public DailyReportScheduler(PositionBook positionBook,
                                DailyLimitTracker dailyLimits,
                                KillSwitch killSwitch,
                                DailyPnlTracker dailyPnl,
                                Notifier notifier) {
        this.positionBook = positionBook;
        this.dailyLimits = dailyLimits;
        this.killSwitch = killSwitch;
        this.dailyPnl = dailyPnl;
        this.notifier = notifier;
    }

    /** 평일 15:50 KST 1회 실행. */
    @Scheduled(cron = "0 50 15 * * MON-FRI", zone = "Asia/Seoul")
    public void sendDailyReport() {
        notifier.notify(NoticeLevel.INFO, buildReport());
    }

    private String buildReport() {
        var positions = positionBook.snapshot();
        StringBuilder sb = new StringBuilder();
        sb.append("=== 일일 성과 리포트 ===\n");
        sb.append("오늘 주문 수: ").append(dailyLimits.todayOrderCount()).append('\n');
        sb.append("오늘 실현손익: ").append(dailyPnl.todayRealizedPnl()).append("원\n");
        sb.append("킬스위치: ").append(killSwitch.isEngaged() ? "작동 중" : "정상").append('\n');
        sb.append("보유 종목 수: ").append(positions.size());
        positions.forEach((symbol, position) ->
                sb.append("\n- ").append(symbol).append(' ')
                        .append(position.quantity()).append("주 @ ").append(position.avgPrice()));
        // TODO Phase 2 후반: 브로커 잔고 연동 후 계좌 평가액(총 자산, 당일 손익) 추가.
        sb.append("\n계좌 평가액: TODO(잔고 연동 후)");
        return sb.toString();
    }
}

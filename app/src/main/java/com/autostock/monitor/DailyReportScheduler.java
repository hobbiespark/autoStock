package com.autostock.monitor;

import com.autostock.risk.DailyLimitTracker;
import com.autostock.risk.KillSwitch;
import com.autostock.risk.PositionBook;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 일일 성과 리포트 — 매 평일 15:50(KST) 장 마감(15:30) 직후 오늘 하루 요약을 알림으로 보낸다.
 *
 * <p>포함 내용: 현재 포지션 스냅샷(PositionBook), 오늘 주문 수(DailyLimitTracker),
 * 킬스위치 상태. 계좌 평가액은 TODO — 브로커 잔고 연동(Phase 2 후반, PLAN 9절) 전까지는
 * 표시할 수 없어 자리만 남겨둔다.
 */
@Component
public class DailyReportScheduler {

    private final PositionBook positionBook;
    private final DailyLimitTracker dailyLimits;
    private final KillSwitch killSwitch;
    private final Notifier notifier;

    public DailyReportScheduler(PositionBook positionBook,
                                DailyLimitTracker dailyLimits,
                                KillSwitch killSwitch,
                                Notifier notifier) {
        this.positionBook = positionBook;
        this.dailyLimits = dailyLimits;
        this.killSwitch = killSwitch;
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

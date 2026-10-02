package com.autostock.common.event;

import java.time.Instant;
import java.util.List;

/**
 * 거시 지표 신선도 경고 이벤트. (스키마 v1 — 2026-10-02 신규, 실행 계획 1.4)
 *
 * <p>risk 모듈의 MacroGuard가 판정에 쓰는 지표(VIX·원/달러)를 {@code macrointel.max-staleness-days}보다 오래
 * 받지 못했을 때 하루 한 번 발행한다. 그동안 신규 매수는 보수 모드로 막힌다(값을 모르면 사지 않는다).
 * monitor 모듈이 받아 텔레그램 WARN으로 알린다 — risk는 알림 수단을 모른다(KillSwitchChanged와 같은 설계).
 *
 * @param indicatorIds     오래된 지표 ID(예: FRED_VIX, ECOS_USDKRW)
 * @param ageDays          그중 가장 오래된 지표의 마지막 수신 후 경과 일수(받은 적 없으면 기동 후 경과)
 * @param maxStalenessDays 기준 일수(설정값)
 * @param timestamp        판정 시각
 */
public record MacroIndicatorStale(
        List<String> indicatorIds,
        long ageDays,
        int maxStalenessDays,
        Instant timestamp
) {
}

package com.autostock.risk;

import com.autostock.common.event.MacroIndicator;
import com.autostock.common.event.MacroIndicatorStale;
import com.autostock.macrointel.MacroIntelProperties;
import com.autostock.macrointel.MacroSyncScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * MacroGuard 판정 규칙 검증 — VIX 35(severe) → 킬스위치, VIX/USDKRW 25/1450(caution) →
 * 보수 모드 ON, 임계치 아래로 복귀 → 보수 모드 자동 OFF(MacroGuard 클래스 설명 "판정 규칙" 참고).
 */
class MacroGuardTest {

    // 기본값: VIX caution 25.0 / severe 35.0, USDKRW caution 1450.0 (PLAN 5절 기본값).
    private static final MacroIntelProperties PROPERTIES =
            new MacroIntelProperties(false, "", "", 25.0, 35.0, 1450.0, 7);

    private KillSwitch killSwitch;
    private MacroGuard guard;

    @BeforeEach
    void setUp() {
        ApplicationEventPublisher noopPublisher = event -> { };
        killSwitch = new KillSwitch(noopPublisher, mock(RiskStateStore.class), Clock.systemUTC());
        guard = new MacroGuard(PROPERTIES, killSwitch, event -> { }, java.time.Clock.systemUTC());
    }

    private void publishVix(double value) {
        guard.onMacroIndicator(new MacroIndicator("FRED_VIX", "MARKET", BigDecimal.valueOf(value), null, Instant.now()));
    }

    private void publishUsdKrw(double value) {
        guard.onMacroIndicator(new MacroIndicator("ECOS_USDKRW", "MARKET", BigDecimal.valueOf(value), null, Instant.now()));
    }

    @Test
    void VIX가_35_이상이면_킬스위치가_작동한다() {
        publishVix(35.0);
        assertTrue(killSwitch.isEngaged());
    }

    @Test
    void VIX가_35_미만_25_이상이면_킬스위치는_켜지지_않고_보수모드만_켜진다() {
        publishVix(30.0);
        assertFalse(killSwitch.isEngaged());
        assertTrue(guard.isConservativeMode());
    }

    @Test
    void VIX가_25_미만이면_보수모드가_켜지지_않는다() {
        publishVix(20.0);
        assertFalse(guard.isConservativeMode());
    }

    @Test
    void USDKRW가_1450_이상이면_보수모드가_켜진다() {
        publishUsdKrw(1460.0);
        assertTrue(guard.isConservativeMode());
    }

    @Test
    void 두_조건이_모두_해제되면_보수모드가_자동으로_꺼진다() {
        publishVix(30.0);
        assertTrue(guard.isConservativeMode(), "VIX 임계 초과로 보수 모드가 켜져 있어야 함");

        publishVix(20.0); // VIX만 해제 — USDKRW는 아직 안 들어왔으므로(null) 여전히 caution 아님
        assertFalse(guard.isConservativeMode(), "VIX·USDKRW 모두 임계치 아래면 보수 모드가 자동으로 꺼져야 함");
    }

    @Test
    void VIX는_해제됐지만_USDKRW가_여전히_임계_초과면_보수모드가_유지된다() {
        publishVix(30.0);
        publishUsdKrw(1500.0);
        assertTrue(guard.isConservativeMode());

        publishVix(20.0); // VIX만 해제 — USDKRW는 여전히 1500 (caution 유지)
        assertTrue(guard.isConservativeMode(), "USDKRW가 아직 임계 초과라 보수 모드가 유지돼야 함");
    }

    @Test
    void 킬스위치는_보수모드와_달리_VIX가_내려가도_자동으로_해제되지_않는다() {
        publishVix(35.0);
        assertTrue(killSwitch.isEngaged());

        publishVix(10.0); // VIX가 완전히 정상으로 돌아와도
        assertTrue(killSwitch.isEngaged(), "킬스위치는 사람의 명시적 release()로만 해제돼야 함(MacroGuard가 자동 해제하면 안 됨)");
    }

    // ── 지표 신선도(실행 계획 1.4, BE-P1-8) — 수집이 켜져 있을 때만 본다 ─────────────────────────

    /** 수집 켜짐(enabled=true) + 기준 7일. */
    private static final MacroIntelProperties ENABLED =
            new MacroIntelProperties(true, "fk", "ek", 25.0, 35.0, 1450.0, 7);

    /** 2026-09-22(화) 09:00 KST 기동. */
    private static final Instant BOOT = Instant.parse("2026-09-22T00:00:00Z");

    private final MutableClock clock = new MutableClock(BOOT);
    private final List<Object> events = new ArrayList<>();

    private MacroGuard enabledGuard() {
        return new MacroGuard(ENABLED, killSwitch, events::add, clock);
    }

    private static void receive(MacroGuard g, String indicatorId, double value, Instant at) {
        g.onMacroIndicator(new MacroIndicator(indicatorId, "MARKET", BigDecimal.valueOf(value), null, at));
    }

    @Test
    void 판정_지표를_7일까지는_신선으로_8일째는_오래됨으로_보고_매수를_막는다() {
        MacroGuard g = enabledGuard();
        receive(g, "FRED_VIX", 18.0, BOOT);
        receive(g, "ECOS_USDKRW", 1390.0, BOOT);

        clock.set(BOOT.plus(Duration.ofDays(7)));
        assertEquals(Optional.empty(), g.buyBlockReason(), "정확히 7일은 아직 신선");

        clock.set(BOOT.plus(Duration.ofDays(8)));
        assertEquals(Optional.of(MacroGuard.REASON_STALE), g.buyBlockReason());
        assertTrue(g.isConservativeMode());
        assertFalse(killSwitch.isEngaged(), "오래된 값으로 킬스위치를 켜지 않는다");
    }

    @Test
    void 연휴에도_수집이_매일_같은_값을_다시_보내면_오래됨이_아니다() {
        // 추석(9/24~26)·주말 — ECOS 관측치는 9/23에 멈춰 있지만 따라잡기 수집은 매일 성공해 같은 값을 재발행한다
        MacroGuard g = enabledGuard();
        for (int day = 0; day <= 9; day++) {
            Instant at = BOOT.plus(Duration.ofDays(day));
            receive(g, "FRED_VIX", 18.0, at);
            receive(g, "ECOS_USDKRW", 1390.0, at);
        }

        clock.set(BOOT.plus(Duration.ofDays(9)).plus(Duration.ofHours(1)));
        assertEquals(Optional.empty(), g.buyBlockReason());
    }

    @Test
    void 받은_적_없는_지표는_기동_시각부터_잰다() {
        MacroGuard g = enabledGuard();
        receive(g, "FRED_VIX", 18.0, BOOT.plus(Duration.ofDays(7)));   // VIX만 계속 들어오고 원/달러는 한 번도 없음

        clock.set(BOOT.plus(Duration.ofDays(7)));
        assertEquals(Optional.empty(), g.buyBlockReason());
        clock.set(BOOT.plus(Duration.ofDays(8)));
        assertEquals(List.of("ECOS_USDKRW"), g.staleIndicators(clock.instant()));
    }

    @Test
    void 수집이_꺼져_있으면_오래됨을_보지_않는다() {
        MacroGuard g = new MacroGuard(PROPERTIES, killSwitch, events::add, clock);   // enabled=false

        clock.set(BOOT.plus(Duration.ofDays(30)));

        assertEquals(Optional.empty(), g.buyBlockReason());
        g.checkFreshness();
        assertTrue(events.isEmpty());
    }

    @Test
    void 임계_초과가_오래됨보다_먼저인_사유다() {
        MacroGuard g = enabledGuard();
        receive(g, "FRED_VIX", 30.0, BOOT);
        clock.set(BOOT.plus(Duration.ofDays(8)));

        assertEquals(Optional.of(MacroGuard.REASON_THRESHOLD), g.buyBlockReason());
    }

    @Test
    void 오래됨_경고는_하루_한_번만_내고_다시_받으면_풀린다() {
        MacroGuard g = enabledGuard();
        receive(g, "FRED_VIX", 18.0, BOOT);
        receive(g, "ECOS_USDKRW", 1390.0, BOOT);

        clock.set(BOOT.plus(Duration.ofDays(9)));   // 10/1 09:00 KST
        g.checkFreshness();
        clock.set(BOOT.plus(Duration.ofDays(9)).plus(Duration.ofHours(5)));
        g.checkFreshness();                         // 같은 날 — 다시 내지 않는다
        assertEquals(1, events.size());
        MacroIndicatorStale stale = (MacroIndicatorStale) events.get(0);
        assertEquals(List.of("FRED_VIX", "ECOS_USDKRW"), stale.indicatorIds());
        assertEquals(9, stale.ageDays());
        assertEquals(7, stale.maxStalenessDays());

        clock.set(BOOT.plus(Duration.ofDays(10)));  // 다음 날 — 다시 낸다
        g.checkFreshness();
        assertEquals(2, events.size());

        receive(g, "FRED_VIX", 18.0, clock.instant());
        receive(g, "ECOS_USDKRW", 1390.0, clock.instant());
        assertEquals(Optional.empty(), g.buyBlockReason());
    }

    @Test
    void 지표_ID는_발행하는_쪽_상수와_같다() {
        assertEquals(MacroSyncScheduler.INDICATOR_VIX, MacroGuard.INDICATOR_VIX);
        assertEquals(MacroSyncScheduler.INDICATOR_USDKRW, MacroGuard.INDICATOR_USDKRW);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

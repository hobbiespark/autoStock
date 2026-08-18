package com.autostock.risk;

import com.autostock.common.event.MacroIndicator;
import com.autostock.macrointel.MacroIntelProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MacroGuard 판정 규칙 검증 — VIX 35(severe) → 킬스위치, VIX/USDKRW 25/1450(caution) →
 * 보수 모드 ON, 임계치 아래로 복귀 → 보수 모드 자동 OFF(MacroGuard 클래스 설명 "판정 규칙" 참고).
 */
class MacroGuardTest {

    // 기본값: VIX caution 25.0 / severe 35.0, USDKRW caution 1450.0 (PLAN 5절 기본값).
    private static final MacroIntelProperties PROPERTIES =
            new MacroIntelProperties(false, "", "", 25.0, 35.0, 1450.0);

    private KillSwitch killSwitch;
    private MacroGuard guard;

    @BeforeEach
    void setUp() {
        ApplicationEventPublisher noopPublisher = event -> { };
        killSwitch = new KillSwitch(noopPublisher);
        guard = new MacroGuard(PROPERTIES, killSwitch);
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
}

package com.autostock.risk;

import com.autostock.common.event.MacroIndicator;
import com.autostock.macrointel.MacroIntelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 거시 지표 기반 국면 판정기 (PLAN 5절 1단계 — 규칙 기반 macro-intel).
 *
 * <p>{@code macrointel} 모듈이 발행하는 {@link MacroIndicator} 이벤트를 구독해 지표별 최신값을
 * 보관하고, VIX·원/달러 환율 임계치 규칙에 따라 <b>보수 모드</b>를 켜고 끄거나 필요시
 * {@link KillSwitch}를 작동시킨다.
 *
 * <p><b>이 클래스가 risk 모듈에 있는 이유</b>: risk/package-info.java의 원칙 — "한도·차단
 * 판단은 risk 소유". macrointel은 수집·발행만 담당하고(그 모듈 package-info.java 참고), 그
 * 값을 해석해 실제로 매매를 제한하는 책임은 risk가 진다. macrointel → risk 방향 참조는
 * 전혀 없다(macrointel은 risk의 존재를 모른다) — risk → macrointel 단방향이라 순환이 생기지
 * 않는다({@code ModularityTests} 검증). 같은 이유로 {@link DisclosureBlacklist}도 risk에 둔다.
 *
 * <h2>판정 규칙 (MacroIntelProperties 임계치)</h2>
 * <ul>
 *   <li>VIX ≥ {@code vixSevereThreshold}(기본 35.0) → {@link KillSwitch#engage} —
 *       극단 변동성 국면은 전면 정지(신규 진입·청산 시그널 모두 차단, 사람이 수동 해제).</li>
 *   <li>VIX ≥ {@code vixCautionThreshold}(기본 25.0) <b>또는</b> USDKRW ≥
 *       {@code usdKrwCautionThreshold}(기본 1450.0) → <b>보수 모드 ON</b>.</li>
 *   <li>두 조건이 모두 해제되면(각 임계치 아래로 내려오면) 보수 모드는 <b>자동으로 OFF</b>된다
 *       — 킬스위치와 달리 보수 모드는 사람 개입 없이도 국면이 풀리면 스스로 풀리는 완화
 *       단계이기 때문이다(아래 "보수 모드 vs 킬스위치" 참고). 킬스위치 자체는 절대 자동
 *       해제하지 않는다({@link KillSwitch} 클래스 설명의 기존 원칙 그대로 유지).</li>
 * </ul>
 *
 * <h2>보수 모드 vs 킬스위치 — 왜 구분하는가</h2>
 * 킬스위치는 <b>전면 정지</b>다(매수·매도 시그널 모두 RiskGate에서 차단, PLAN 8절 "시스템"
 * 계층). 반면 보수 모드는 <b>신규 진입(매수)만 금지</b>하고 청산(매도)은 그대로 허용하는
 * 더 완화된 단계다 — 위험 국면이라도 이미 보유한 포지션을 정리할 길은 열어 둬야 한다는
 * 판단(PLAN 8절 "국면" 계층 — "VIX·환율 임계 초과 시 보수 모드"). 킬스위치가 "이 순간 아무것도
 * 하지 마라"라면, 보수 모드는 "새 도박은 걸지 말고 있는 것만 정리해도 된다"는 뜻이다.
 */
@Component
public class MacroGuard {

    private static final Logger log = LoggerFactory.getLogger(MacroGuard.class);

    /** 이 클래스가 판정에 실제로 쓰는 indicatorId — macrointel.MacroSyncScheduler가 발행하는 값과 일치해야 한다. */
    static final String INDICATOR_VIX = "FRED_VIX";
    static final String INDICATOR_USDKRW = "ECOS_USDKRW";

    private final MacroIntelProperties properties;
    private final KillSwitch killSwitch;

    /** 지표별 최신값 — DXY/기준금리 등 판정에 쓰이지 않는 지표도 그대로 보관한다(향후 규칙 확장 대비). */
    private final Map<String, BigDecimal> latestValues = new ConcurrentHashMap<>();

    private volatile boolean conservativeMode = false;

    public MacroGuard(MacroIntelProperties properties, KillSwitch killSwitch) {
        this.properties = properties;
        this.killSwitch = killSwitch;
    }

    /** 지금 보수 모드인지 — {@link RiskGate}가 매수 사이징 직전에 참조한다. */
    public boolean isConservativeMode() {
        return conservativeMode;
    }

    @EventListener
    public void onMacroIndicator(MacroIndicator event) {
        latestValues.put(event.indicatorId(), event.value());
        evaluate();
    }

    /** 최신값을 모아 킬스위치·보수 모드 규칙을 재평가한다 — 이벤트 하나가 들어올 때마다 전체를 다시 판정한다. */
    private void evaluate() {
        BigDecimal vix = latestValues.get(INDICATOR_VIX);
        BigDecimal usdKrw = latestValues.get(INDICATOR_USDKRW);

        if (vix != null && vix.doubleValue() >= properties.vixSevereThreshold()) {
            // KillSwitch.engage는 이미 켜져 있으면 조용히 무시한다(compareAndSet) — 매번
            // 이벤트가 올 때마다 중복 로그가 남지 않는다.
            killSwitch.engage("VIX 급등(%.2f ≥ 임계 %.2f) — 전면 정지"
                    .formatted(vix.doubleValue(), properties.vixSevereThreshold()));
        }

        boolean vixCaution = vix != null && vix.doubleValue() >= properties.vixCautionThreshold();
        boolean usdKrwCaution = usdKrw != null && usdKrw.doubleValue() >= properties.usdKrwCautionThreshold();
        boolean nextConservative = vixCaution || usdKrwCaution;

        if (nextConservative != conservativeMode) {
            conservativeMode = nextConservative;
            if (conservativeMode) {
                log.warn("보수 모드 ON — VIX={}(임계 {}), USDKRW={}(임계 {}) — 신규 매수 금지, 매도(청산)는 허용",
                        vix, properties.vixCautionThreshold(), usdKrw, properties.usdKrwCautionThreshold());
            } else {
                log.info("보수 모드 OFF — VIX·USDKRW 모두 임계치 아래로 해제됨(VIX={}, USDKRW={})", vix, usdKrw);
            }
        }
    }
}

package com.autostock.risk;

import com.autostock.common.event.MacroIndicator;
import com.autostock.common.event.MacroIndicatorStale;
import com.autostock.common.util.MarketConstants;
import com.autostock.macrointel.MacroIntelProperties;
import com.autostock.macrointel.MacroSyncScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 *
 * <h2>지표 신선도(실행 계획 1.4, BE-P1-8, 2026-10-02 — aiDoc/macro-staleness.md)</h2>
 * 판정 지표(VIX·원/달러)를 {@code macrointel.max-staleness-days}(기본 7)일보다 오래 받지 못하면 값을 "알 수 없음"으로
 * 보고 보수 모드와 같이 <b>신규 매수만</b> 막는다 — 수집이 며칠째 실패하는데 마지막 값으로 계속 매수를 허용하던 결함.
 * 받은 적이 없으면 기동 시각부터 잰다. 수집이 꺼져 있으면({@code macrointel.enabled=false}) 보지 않는다(데이터가 올 일이 없다).
 * 오래됨은 이벤트의 발행 시각 기준이다 — 연휴에도 수집은 매일 성공(최신 관측치 재발행)하므로 걸리지 않는다.
 * 오래된 동안 매시 점검({@link #checkFreshness})이 하루 한 번 WARN과 {@link MacroIndicatorStale}(텔레그램)을 낸다.
 * 킬스위치(VIX 35)는 새 값이 들어올 때만 판정한다 — 오래된 값으로 킬스위치를 켜지는 않는다.
 */
@Component
public class MacroGuard {

    private static final Logger log = LoggerFactory.getLogger(MacroGuard.class);

    /** 이 클래스가 판정에 실제로 쓰는 indicatorId — 발행하는 쪽(macrointel) 상수를 그대로 쓴다(단일 원천, BE-P2-14). */
    static final String INDICATOR_VIX = MacroSyncScheduler.INDICATOR_VIX;
    static final String INDICATOR_USDKRW = MacroSyncScheduler.INDICATOR_USDKRW;

    /** 판정에 쓰는 지표 — 신선도도 이 둘만 본다(DXY·기준금리는 보관만). */
    static final List<String> JUDGED_INDICATORS = List.of(INDICATOR_VIX, INDICATOR_USDKRW);

    /** 매수 거부 사유 — RiskGate 로그·판단 근거(SignalDecision)에 그대로 실린다. */
    static final String REASON_THRESHOLD = "보수 모드(거시 국면 경계, VIX/환율 임계 초과) — 신규 매수 거부";
    static final String REASON_STALE = "보수 모드(거시 지표 오래됨 — 수집 확인 필요) — 신규 매수 거부";

    private final MacroIntelProperties properties;
    private final KillSwitch killSwitch;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    /** 받은 적 없는 지표의 신선도 기준 — 기동 시각. */
    private final Instant startedAt;

    /** 지표별 최신값 — DXY/기준금리 등 판정에 쓰이지 않는 지표도 그대로 보관한다(향후 규칙 확장 대비). */
    private final Map<String, BigDecimal> latestValues = new ConcurrentHashMap<>();
    /** 지표별 마지막 수신 시각(이벤트 발행 시각). */
    private final Map<String, Instant> receivedAt = new ConcurrentHashMap<>();

    private volatile boolean conservativeMode = false;
    /** 오래됨 경고를 마지막으로 낸 날(KST) — 하루 한 번만. */
    private volatile LocalDate lastStaleAlertDate;
    /** 오래됨을 알린 뒤 아직 회복 로그를 남기지 않았는지. */
    private volatile boolean staleAnnounced;

    public MacroGuard(MacroIntelProperties properties, KillSwitch killSwitch,
                      ApplicationEventPublisher publisher, Clock clock) {
        this.properties = properties;
        this.killSwitch = killSwitch;
        this.publisher = publisher;
        this.clock = clock;
        this.startedAt = clock.instant();
    }

    /** 지금 신규 매수를 막는지 — 임계 초과 보수 모드이거나 판정 지표가 오래됐으면 true. */
    public boolean isConservativeMode() {
        return buyBlockReason().isPresent();
    }

    /**
     * 신규 매수를 막는 이유 — {@link RiskGate}가 매수 사이징 직전에 참조한다. 임계 초과가 우선이고, 그다음이 지표 오래됨.
     * 막지 않으면 빈 값.
     */
    public Optional<String> buyBlockReason() {
        if (conservativeMode) {
            return Optional.of(REASON_THRESHOLD);
        }
        if (!staleIndicators(clock.instant()).isEmpty()) {
            return Optional.of(REASON_STALE);
        }
        return Optional.empty();
    }

    @EventListener
    public void onMacroIndicator(MacroIndicator event) {
        latestValues.put(event.indicatorId(), event.value());
        Instant at = event.timestamp() == null ? clock.instant() : event.timestamp();
        receivedAt.merge(event.indicatorId(), at, (a, b) -> a.isAfter(b) ? a : b);
        evaluate();
        if (staleAnnounced && staleIndicators(clock.instant()).isEmpty()) {
            staleAnnounced = false;
            log.info("거시 지표 다시 수신 — 오래됨 해제(신규 매수 제한은 임계 판정만 남음)");
        }
    }

    /**
     * 판정 지표 중 {@code max-staleness-days}보다 오래 받지 못한 것(받은 적 없으면 기동 시각부터 잰다).
     * 수집이 꺼져 있으면 빈 목록 — 경계: 정확히 그 일수면 아직 신선하다.
     */
    List<String> staleIndicators(Instant now) {
        if (!properties.enabled()) {
            return List.of();
        }
        Duration limit = Duration.ofDays(properties.maxStalenessDays());
        return JUDGED_INDICATORS.stream()
                .filter(id -> age(id, now).compareTo(limit) > 0)
                .toList();
    }

    private Duration age(String indicatorId, Instant now) {
        return Duration.between(receivedAt.getOrDefault(indicatorId, startedAt), now);
    }

    /**
     * 신선도 점검 — 매시 10분(KST, 08:30 수집·05분 따라잡기 뒤). 오래된 지표가 있으면 하루 한 번 WARN 로그와
     * {@link MacroIndicatorStale}(monitor가 텔레그램으로 알림)를 낸다. 매수 제한 자체는 이 점검과 무관하게
     * {@link #buyBlockReason}이 매번 계산한다.
     */
    @Scheduled(cron = "0 10 * * * *", zone = "Asia/Seoul")
    public void checkFreshness() {
        Instant now = clock.instant();
        List<String> stale = staleIndicators(now);
        if (stale.isEmpty()) {
            return;
        }
        staleAnnounced = true;
        LocalDate today = LocalDate.ofInstant(now, MarketConstants.KST);
        if (today.equals(lastStaleAlertDate)) {
            return;
        }
        lastStaleAlertDate = today;
        long ageDays = stale.stream().mapToLong(id -> age(id, now).toDays()).max().orElse(0);
        log.warn("거시 지표 오래됨 {} — 가장 오래된 것 {}일(기준 {}일). 수집 확인 필요 — 그동안 신규 매수 금지(보수 모드)",
                stale, ageDays, properties.maxStalenessDays());
        publisher.publishEvent(new MacroIndicatorStale(stale, ageDays, properties.maxStalenessDays(), now));
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

package com.autostock.macrointel;

import com.autostock.common.event.MacroIndicator;
import com.autostock.common.util.MarketConstants;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 거시 지표 수집 배치 — 매 평일 08:30 KST(정규장 09:00 시작 전) FRED/ECOS에서 VIX·달러인덱스·
 * 원/달러 환율·기준금리를 조회해 {@link MacroIndicator} 이벤트로 발행한다.
 *
 * <p>indicatorId 스키마: {@code FRED_VIX} / {@code FRED_DXY} / {@code ECOS_USDKRW} /
 * {@code ECOS_BASE_RATE}, scope는 전부 {@code "MARKET"}(개별 종목이 아닌 시장 전체 지표).
 * 이 이벤트를 구독해 실제로 매매를 제한하는 판단은 이 모듈이 하지 않는다 — risk 모듈의
 * {@code MacroGuard}가 담당한다(package-info.java "장애가 매매 루프를 막지 않도록 격리" 원칙,
 * 이 배치는 수집·발행까지만 책임진다).
 *
 * <p><b>소스별 실패 격리</b>({@code market.HolidaySyncService}와 같은 정책): FRED와 ECOS는
 * 완전히 독립적인 소스다 — FRED 조회 실패(키 미발급, 타임아웃 등)가 이어지는 ECOS 수집을
 * 막지 않고, 네 지표 중 하나가 실패해도 나머지 지표는 정상 발행된다. 각 지표 수집을
 * try-catch로 개별 격리한 이유가 이것이다.
 */
@Component
public class MacroSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(MacroSyncScheduler.class);

    public static final String INDICATOR_VIX = "FRED_VIX";
    public static final String INDICATOR_DXY = "FRED_DXY";
    public static final String INDICATOR_USDKRW = "ECOS_USDKRW";
    public static final String INDICATOR_BASE_RATE = "ECOS_BASE_RATE";

    private static final String SCOPE_MARKET = "MARKET";

    private final MacroIntelProperties properties;
    private final FredClient fredClient;
    private final EcosClient ecosClient;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public MacroSyncScheduler(MacroIntelProperties properties, FredClient fredClient,
                              EcosClient ecosClient, ApplicationEventPublisher publisher, Clock clock) {
        this.clock = clock;
        this.properties = properties;
        this.fredClient = fredClient;
        this.ecosClient = ecosClient;
        this.publisher = publisher;
    }

    /**
     * 지표마다 오늘(KST) 받은 날 — 따라잡기는 못 받은 지표만 다시 받는다(10/7 로그 점검 L-5, 2026-10-13). 예전에는 네 지표 중
     * 하나라도 받으면 그날 성공으로 적어, ECOS만 실패한 날(10/12 07:14 실측)도 다시 받지 않았다.
     */
    private final Map<String, LocalDate> collectedOn = new ConcurrentHashMap<>();

    /** 매 평일 08:30 KST 1회 실행 — 장 시작 전 거시 지표 수집. */
    @Scheduled(cron = "0 30 8 * * MON-FRI", zone = "Asia/Seoul")
    public void syncScheduled() {
        syncNow();
    }

    // ── 따라잡기(catch-up) — 2026-09-18 운영 교훈 ─────────────────────────────────────
    // 배치가 cron 시각에만 돌면 (1) 낮에 재기동한 날은 그날 내내 데이터가 없고 (2) 그 시각에 API가 잠깐
    // 실패하면 다음 날까지 복구 기회가 없다. 그래서 ① 기동 직후 1회, ② 매시 05분에 "오늘 성공 기록이
    // 없으면" 다시 시도한다. 성공한 날은 시간별 점검이 아무 일도 하지 않으므로 외부 API 부하는 하루 1회 그대로다.
    // 키가 비어 있으면(CI 등) 시도 자체를 건너뛴다 — 외부 호출로 테스트가 느려지거나 실패하는 일을 막는다.
    // 2026-10-13: 성공 기록을 지표마다 둔다 — 따라잡기는 그날 못 받은 지표만, 키가 있는 소스만 다시 받는다.

    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        catchUp("기동");
    }

    @Scheduled(cron = "0 5 * * * *", zone = "Asia/Seoul")
    public void catchUpHourly() {
        catchUp("시간별 점검");
    }

    void catchUp(String reason) {
        if (!properties.enabled()) {
            return;
        }
        boolean fred = !isBlank(properties.fredApiKey());
        boolean ecos = !isBlank(properties.ecosApiKey());
        if (!fred && !ecos) {
            log.info("macro-intel 따라잡기({}) 스킵 — API 키 없음", reason);
            return;
        }
        LocalDate today = LocalDate.now(clock.withZone(MarketConstants.KST));
        List<String> missing = new ArrayList<>();
        if (fred) {
            missing.add(INDICATOR_VIX);
            missing.add(INDICATOR_DXY);
        }
        if (ecos) {
            missing.add(INDICATOR_USDKRW);
            missing.add(INDICATOR_BASE_RATE);
        }
        missing.removeIf(id -> today.equals(collectedOn.get(id)));
        if (missing.isEmpty()) {
            return;
        }
        log.info("macro-intel 따라잡기({}) — 오늘 못 받은 지표 {}, 지금 수집", reason, missing);
        missing.forEach(this::collect);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 수동 트리거 — 운영자가 즉시 재수집하고 싶을 때(예: 키 재발급 직후 확인, 배치 실패 후
     * 재시도) 직접 호출할 수 있도록 public으로 둔다({@code HolidaySyncService.syncYear}와 같은
     * 이유).
     *
     * <p>{@code enabled=false}면 아무 것도 하지 않는다(키 미발급 상태의 기본값) — 예외를
     * 던지지 않고 조용히 스킵하는 이유는, cron 스케줄이 비활성 상태에서도 매 평일 그대로
     * 실행되기 때문이다(로그만 남긴다).
     */
    public void syncNow() {
        if (!properties.enabled()) {
            log.info("macro-intel 수집 비활성(macrointel.enabled=false) — 이번 배치 스킵");
            return;
        }
        List.of(INDICATOR_VIX, INDICATOR_DXY, INDICATOR_USDKRW, INDICATOR_BASE_RATE).forEach(this::collect);
    }

    /** 지표 하나를 받아 발행하고, 받았으면 오늘 받은 것으로 적는다. */
    private void collect(String indicatorId) {
        boolean ok = switch (indicatorId) {
            case INDICATOR_VIX -> collectFred(INDICATOR_VIX, FredClient.SERIES_VIX);
            case INDICATOR_DXY -> collectFred(INDICATOR_DXY, FredClient.SERIES_DXY);
            case INDICATOR_USDKRW -> collectEcos(INDICATOR_USDKRW, EcosClient.STAT_CODE_USDKRW);
            case INDICATOR_BASE_RATE -> collectEcos(INDICATOR_BASE_RATE, EcosClient.STAT_CODE_BASE_RATE);
            default -> throw new IllegalArgumentException("모르는 지표: " + indicatorId);
        };
        if (ok) {
            collectedOn.put(indicatorId, LocalDate.now(clock.withZone(MarketConstants.KST)));
        }
    }

    private boolean collectFred(String indicatorId, String seriesId) {
        try {
            var obs = fredClient.fetchLatest(seriesId);
            obs.ifPresentOrElse(
                    o -> publish(indicatorId, o.value(), "FRED series=" + seriesId),
                    () -> log.warn("FRED {} 관측치 없음 — 이번 수집 스킵", indicatorId));
            return obs.isPresent();
        } catch (RuntimeException e) {
            // FredClient.fetchLatest 자체가 이미 내부에서 예외를 흡수하므로 정상 경로에서는
            // 여기까지 오지 않는다 — 그래도 소스별 격리 원칙을 클래스 레벨에서도 보장하기
            // 위한 방어적 이중 안전장치다(클래스 설명 "소스별 실패 격리" 참고).
            log.error("FRED {} 수집 실패 — ECOS 수집은 계속 진행", indicatorId, e);
            return false;
        }
    }

    private boolean collectEcos(String indicatorId, String statCode) {
        try {
            var obs = ecosClient.fetchLatest(statCode);
            obs.ifPresentOrElse(
                    o -> publish(indicatorId, o.value(), "ECOS statCode=" + statCode),
                    () -> log.warn("ECOS {} 관측치 없음 — 이번 수집 스킵", indicatorId));
            return obs.isPresent();
        } catch (RuntimeException e) {
            log.error("ECOS {} 수집 실패", indicatorId, e);
            return false;
        }
    }

    private void publish(String indicatorId, BigDecimal value, String detail) {
        publisher.publishEvent(new MacroIndicator(indicatorId, SCOPE_MARKET, value, detail, clock.instant()));
        log.info("거시 지표 발행: {}={} ({})", indicatorId, value, detail);
    }
}

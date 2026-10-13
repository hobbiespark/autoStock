package com.autostock.ipo;

import com.autostock.common.event.IpoAlert;
import com.autostock.common.event.RecentListings;
import com.autostock.common.util.MarketConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 공모주 일정 수집·필터 판단 배치 (PLAN.md ADR-9 ①②, 트랙 E2) — 평일 08:20 KST(장 시작 전,
 * macrointel.MacroSyncScheduler의 08:30보다 앞선 이유는 이 배치가 딜 상세까지 여러 건 순회
 * 조회해 더 여유를 두기 위함).
 *
 * <p>절차: 최근 {@code dart.lookback-days}일 list.json 조회(모든 쪽) → corp_code별 상세(estkRs.json)
 * 조회와 공모 종류 판정 → upsert({@code rcept_no} 기준, 상장사 유상증자는 만들지 않음) → 판정 전 옛 딜 판정 →
 * [발행조건확정] 수요예측 결과·확정 공모가 자동 입력 → 상태(IpoStatus) 재계산 → 필터 평가(IpoRecommendation) →
 * 단계별 알림({@link IpoAlertPlanner})에 {@link IpoAlert} 발행.
 *
 * <p>2026-10-02(aiDoc/ipo-demand-forecast.md): 기관경쟁률·의무보유확약비율을 [발행조건확정] 원본에서 자동으로 채우고
 * ({@link IpoDemandForecastCollector}), 상장사 유상증자가 공모주 딜로 들어오던 결함을 고쳤다({@link IpoOfferingClassifier}).
 *
 * <p>2026-10-13(사용자 "로그 확인 후 보완 필요. 공모주 기능 자동화 강화 필요", aiDoc/ipo-automation.md):
 * <ul>
 *   <li>공시 목록을 모든 쪽 읽는다 — 첫 쪽(하루치)만 읽어 놓친 신고가 있었다. 목록 조회가 실패하거나 일부만 읽으면 그날 수집을
 *       성공으로 기록하지 않아 매시 점검이 다시 조회한다(로그 점검 L-5 — 예전에는 실패해도 "완료"로 남았다).</li>
 *   <li>확정 공모가·희망 밴드를 [발행조건확정] 원본에서 읽는다.</li>
 *   <li>알림 단계를 신규·수요예측 결과·청약 D-1·시작·마감·환불·상장 D-1·상장으로 늘리고, 보낸 기록을 단계마다 남긴다(V18 ipo_alert_log).</li>
 *   <li>밤({@value #ALERT_QUIET_FROM_TEXT}~{@value #ALERT_QUIET_UNTIL_TEXT})에는 알림을 보내지 않는다 — 계산만 하고 미뤄 두었다가
 *       07시 이후 매시 점검에서 보낸다(매시 점검은 밤 00:05에도 수집한다).</li>
 *   <li>키움 종목정보 리스트에서 찾은 최근 상장 종목({@link RecentListings}, market 모듈)으로 상장일·종목코드를 채운다.</li>
 * </ul>
 *
 * <p><b>소스별/딜별 실패 격리</b>({@code market.HolidaySyncService}·{@code
 * macrointel.MacroSyncScheduler}와 동일 정책): 한 딜의 상세 조회 실패가 다른 딜 처리를 막지
 * 않는다 — 딜 단위로 try-catch한다.
 *
 * <p>알림은 이 모듈이 직접 보내지 않는다 — {@link IpoAlert} 이벤트만 발행하고 monitor 모듈의
 * 리스너가 Notifier로 이어준다(package-info.java 참고).
 */
@Component
public class IpoSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(IpoSyncScheduler.class);

    private static final String SOURCE_DART = "DART";

    /** 알림을 보내는 시간대 [07:00, 22:00) KST — 밤에 텔레그램이 울리지 않게. */
    static final LocalTime ALERT_FROM = LocalTime.of(7, 0);
    static final LocalTime ALERT_UNTIL = LocalTime.of(22, 0);
    static final String ALERT_QUIET_FROM_TEXT = "22:00";
    static final String ALERT_QUIET_UNTIL_TEXT = "07:00";

    private final DartProperties dartProperties;
    private final IpoFilterProperties filterProperties;
    private final DartClient dartClient;
    private final IpoDealRepository repository;
    private final IpoAlertLogRepository alertLog;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private final IpoOfferingClassifier classifier;
    private final IpoDemandForecastCollector forecastCollector;
    private final IpoListingMatcher listingMatcher;

    /** 수집·알림·상장 확인이 같은 딜을 동시에 고치지 않게 — 정기 배치·매시 점검·상장 확인 이벤트가 겹칠 수 있다. */
    private final Object batchLock = new Object();

    public IpoSyncScheduler(DartProperties dartProperties, IpoFilterProperties filterProperties,
                             DartClient dartClient, IpoDealRepository repository, IpoAlertLogRepository alertLog,
                             ApplicationEventPublisher publisher, Clock clock) {
        this.dartProperties = dartProperties;
        this.filterProperties = filterProperties;
        this.dartClient = dartClient;
        this.repository = repository;
        this.alertLog = alertLog;
        this.publisher = publisher;
        this.clock = clock;
        // 같은 외부 호출(DartClient)·저장소를 쓰는 배치의 단계들이라 빈으로 나누지 않고 여기서 만든다(생성자·테스트 구성 유지)
        this.classifier = new IpoOfferingClassifier(dartClient, repository, clock);
        this.forecastCollector = new IpoDemandForecastCollector(dartClient, repository, clock);
        this.listingMatcher = new IpoListingMatcher(repository, clock);
    }

    /** 매 평일 08:20 KST 1회 실행. */
    @Scheduled(cron = "0 20 8 * * MON-FRI", zone = "Asia/Seoul")
    public void syncScheduled() {
        syncNow();
    }

    /** 마지막으로 DART 공시 목록을 끝까지 읽은 날(KST) — 따라잡기 판단 기준. */
    private volatile LocalDate lastSuccessDate;

    /** 밤이라 보내지 못하고 미룬 알림이 있는가 — 07시 이후 매시 점검이 보낸다. */
    private volatile boolean alertsDeferred;

    // ── 따라잡기(catch-up) — 2026-09-18 운영 교훈 ─────────────────────────────────────
    // 배치가 cron 시각에만 돌면 (1) 낮에 재기동한 날은 그날 내내 데이터가 없고 (2) 그 시각에 API가 잠깐
    // 실패하면 다음 날까지 복구 기회가 없다. 그래서 ① 기동 직후 1회, ② 매시 05분에 "오늘 성공 기록이
    // 없으면" 다시 시도한다. 성공한 날은 시간별 점검이 수집하지 않으므로 외부 API 부하는 하루 1회 그대로다.
    // 키가 비어 있으면(CI 등) 시도 자체를 건너뛴다 — 외부 호출로 테스트가 느려지거나 실패하는 일을 막는다.
    // 2026-10-13: 공시 목록을 끝까지 읽은 날만 성공으로 기록한다. 성공한 날에도 밤에 미룬 알림이 있으면 07시 이후 보낸다.

    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        catchUp("기동");
    }

    @Scheduled(cron = "0 5 * * * *", zone = "Asia/Seoul")
    public void catchUpHourly() {
        catchUp("시간별 점검");
    }

    void catchUp(String reason) {
        if (!dartProperties.enabled() || dartProperties.apiKey() == null || dartProperties.apiKey().isBlank()) {
            return;
        }
        LocalDate today = LocalDate.now(clock.withZone(MarketConstants.KST));
        if (today.equals(lastSuccessDate)) {
            if (alertsDeferred && withinAlertHours()) {
                try {
                    alertPass(today, "밤에 미룬 알림");
                } catch (RuntimeException e) {
                    log.error("ipo 미룬 알림 보내기 실패 — 다음 시간별 점검에서 재시도", e);
                }
            }
            return;
        }
        log.info("ipo 따라잡기({}) — 오늘 수집 기록 없음, 지금 수집", reason);
        try {
            syncNow();
        } catch (RuntimeException e) {
            log.error("ipo 따라잡기 실패 — 다음 시간별 점검에서 재시도", e);
        }
    }

    /**
     * 수동 트리거 — 운영자가 즉시 재수집하고 싶을 때 직접 호출({@code
     * MacroSyncScheduler.syncNow}와 같은 이유). {@code dart.enabled=false}면 조용히 스킵한다.
     */
    public void syncNow() {
        SyncSummary summary = sync();
        if (summary != null) {
            // 성공하면 로그가 하나도 없어 수집이 돌았는지 확인할 수 없었다
            // (2026-10-01 로그 점검 F-4, aiDoc/run-summary-logs.md) — 블랙리스트·매크로 수집과 같은 한 줄 요약.
            if (summary.complete()) {
                log.info(summary.toLogLine());
            } else {
                log.warn(summary.toLogLine());
            }
        }
    }

    /** 수집 본체 — 비활성이면 null, 끝까지 돌면 요약을 돌려준다. 목록을 다 읽지 못하면 성공으로 기록하지 않는다(따라잡기가 재시도). */
    SyncSummary sync() {
        if (!dartProperties.enabled()) {
            log.info("ipo 수집 비활성(dart.enabled=false) — 이번 배치 스킵");
            return null;
        }
        synchronized (batchLock) {
            return syncLocked();
        }
    }

    private SyncSummary syncLocked() {
        LocalDate today = LocalDate.now(clock.withZone(MarketConstants.KST));
        LocalDate since = today.minusDays(dartProperties.lookbackDays());

        DartClient.RecentFilings filings = dartClient.fetchRecentEquityFilings(since, today);
        List<DartClient.DealNotice> deals = filings.deals();
        int created = 0;
        int excluded = 0;
        int skipped = 0;
        int failed = 0;
        Set<String> lookedUpCorps = new HashSet<>();
        for (DartClient.DealNotice deal : deals) {
            try {
                switch (syncOneDeal(deal, today)) {
                    case CREATED -> created++;
                    case EXCLUDED -> excluded++;
                    case UPDATED -> { }
                }
                lookedUpCorps.add(deal.corpCode());
            } catch (OptimisticLockingFailureException e) {
                skipped++;
                log.info("공모주 딜 동기화 저장 생략(동시 수동 입력, 다음 배치 재시도) — corpName={}", deal.corpName());
            } catch (RuntimeException e) {
                failed++;
                log.error("공모주 딜 동기화 실패(스킵) — corpName={}, rceptNo={}",
                        deal.corpName(), deal.rceptNo(), e);
            }
        }

        // V11 이전에 들어온 딜 등 공모 종류를 모르는 딜을 조금씩 판정하고, 수요예측 지표·확정 공모가를 채운다 — 단계마다 실패를 격리한다
        int classified = runStep("공모 종류 판정", () -> classifier.backfill(today, lookedUpCorps), 0);
        IpoDemandForecastCollector.Result filled = runStep("수요예측 지표·확정 공모가 자동 입력",
                () -> forecastCollector.collect(today), IpoDemandForecastCollector.Result.NONE);

        // 상태·알림 재계산은 이번 배치에서 새로 잡히지 않은 기존 딜(예: list.json lookback
        // 창 밖으로 밀려난 딜)도 포함해야 하므로 전체를 다시 훑는다.
        List<IpoDealEntity> all = repository.findAll();
        for (IpoDealEntity entity : all) {
            recalculateStatus(entity, today);
            if (!entity.isRightsOffering()) { // 상장사 유상증자는 권고·알림 대상이 아니다
                evaluateFilter(entity);
            }
        }
        int alerts = runStep("알림", () -> runAlerts(all, today), 0);
        for (IpoDealEntity entity : all) {
            try {
                repository.save(entity);
            } catch (OptimisticLockingFailureException e) {
                // 조회 뒤 수동 입력(IpoDealCommandService)이 먼저 저장됐다 — 그 입력을 덮지 않고 이번엔
                // 건너뛴다. 상태·필터는 다음 배치(또는 입력 직후 재평가)에서 다시 맞춰진다.
                log.info("공모주 딜 재계산 저장 생략(동시 수동 입력) — corpName={}", entity.getCorpName());
            }
        }
        if (filings.complete()) {
            lastSuccessDate = today; // 목록을 끝까지 읽었으면 오늘 수집으로 인정(개별 딜 실패는 위에서 격리)
        }
        return new SyncSummary(since, today, filings.complete(), filings.pagesRead(), filings.totalPages(), deals.size(),
                created, excluded, skipped, failed, classified, filled.metricsFilled(), filled.pricesFilled(), all.size(),
                alerts, alertsDeferred);
    }

    private <T> T runStep(String name, java.util.function.Supplier<T> step, T fallback) {
        try {
            return step.get();
        } catch (RuntimeException e) {
            log.error("공모주 배치 단계 실패({}) — 다음 배치에서 다시 시도", name, e);
            return fallback;
        }
    }

    /**
     * 수집 요약(2026-10-01 로그 점검 F-4, 2026-10-02 유상증자 제외·지표 자동 입력 추가, 2026-10-13 목록 쪽수·확정가·알림 추가).
     *
     * @param complete      공시 목록을 끝까지 읽었는가 — 아니면 매시 점검이 다시 수집한다
     * @param pagesRead     읽은 목록 쪽 수
     * @param totalPages    목록 전체 쪽 수
     * @param filings       조회 기간의 증권신고(지분증권) 건수
     * @param created       이번에 새로 만든 딜 수
     * @param excluded      상장사 유상증자라 딜로 만들지 않은 건수
     * @param skipped       동시 수동 입력으로 저장을 건너뛴 건수(다음 배치 재시도)
     * @param failed        개별 동기화 실패 건수
     * @param classified    공모 종류를 이번에 판정한 옛 딜 수
     * @param metricsFilled 수요예측 지표를 자동 입력한 회사 수
     * @param pricesFilled  확정 공모가를 자동 입력한 회사 수
     * @param totalDeals    상태·필터·알림을 다시 계산한 전체 딜 수
     * @param alerts        이번에 보낸 알림 수(신규 모음은 한 통)
     * @param alertsDeferred 밤이라 알림을 미뤘는가
     */
    record SyncSummary(LocalDate since, LocalDate until, boolean complete, int pagesRead, int totalPages, int filings,
                       int created, int excluded, int skipped, int failed, int classified, int metricsFilled,
                       int pricesFilled, int totalDeals, int alerts, boolean alertsDeferred) {

        String toLogLine() {
            String head = complete ? "공모주 수집 완료" : "공모주 수집 미완료(공시 목록을 끝까지 읽지 못함 — 다음 시간별 점검에서 다시)";
            String alertText = alertsDeferred ? "알림은 밤이라 미룸(07시 이후 보냄)" : "알림 %d건".formatted(alerts);
            return ("%s — %s~%s 증권신고(지분증권) %d건(목록 %d/%d쪽, 신규 딜 %d·유상증자 제외 %d·저장 생략 %d·실패 %d), "
                    + "옛 딜 공모 종류 판정 %d건, 자동 입력 수요예측 지표 %d·확정 공모가 %d개 회사, 전체 딜 %d건 상태 재계산, %s")
                    .formatted(head, since, until, filings, pagesRead, totalPages, created, excluded, skipped, failed,
                            classified, metricsFilled, pricesFilled, totalDeals, alertText);
        }
    }

    /** 공시 한 건 처리 결과. */
    private enum DealOutcome { CREATED, UPDATED, EXCLUDED }

    /**
     * 딜 1건 동기화. 상장사 유상증자로 판정되면 새 딜을 만들지 않는다(이미 있으면 판정만 남겨 화면·알림에서 뺀다).
     *
     * <p>주요정보 조회 기간은 {@link IpoOfferingClassifier#LOOKUP_DAYS}일 — estkRs는 회사의 최신 신고서 접수일이 기간에
     * 걸려야 행을 준다. 이 접수번호의 상세는 그 최신 신고서일 때만 있다([발행조건확정]·옛 정정 신고서는 상세 없음).
     */
    private DealOutcome syncOneDeal(DartClient.DealNotice deal, LocalDate today) {
        Optional<IpoDealEntity> existing = repository.findByRceptNo(deal.rceptNo());
        Optional<DartClient.OfferingLookup> lookup = dartClient.fetchOffering(deal.corpCode(), deal.rceptNo(),
                today.minusDays(IpoOfferingClassifier.LOOKUP_DAYS), today);
        OfferingKind kind = classifier.kindOf(deal, lookup.map(DartClient.OfferingLookup::kind).orElse(null), existing);
        if (existing.isEmpty() && kind == OfferingKind.RIGHTS) {
            log.info("상장사 유상증자 — 공모주 딜로 만들지 않음: {} {} (rceptNo={}, 모집방법 {})", deal.corpName(),
                    deal.reportName(), deal.rceptNo(), lookup.map(DartClient.OfferingLookup::offeringMethod).orElse("확인 못 함"));
            return DealOutcome.EXCLUDED;
        }
        IpoDealEntity entity = existing
                .orElseGet(() -> new IpoDealEntity(deal.corpCode(), deal.corpName(), deal.rceptNo(), SOURCE_DART, clock.instant()));
        lookup.map(DartClient.OfferingLookup::detail)
                .ifPresentOrElse(
                        detail -> entity.applyOfferingDetail(detail, clock.instant()),
                        () -> logDetailMissing(deal, today, lookup.isPresent()));
        entity.classifyOffering(kind, clock.instant());
        repository.save(entity);
        classifier.propagate(deal.corpCode(), kind);
        return existing.isEmpty() ? DealOutcome.CREATED : DealOutcome.UPDATED;
    }

    /**
     * 상세(estkRs.json) 미제공 로그 — 공시 당일~익일은 DART 주요정보 DB 반영 지연이 정상이다
     * (실측: 엠비디 rcept_no=20260918000439 — 9/19 00:48 결과 없음 → 이후 배치에서 채워짐;
     * 9/23 이렘·티앤이코리아도 당일 공시). 딜은 매 배치 다시 조회되므로 자동 복구된다.
     * 공시 후 2일이 지나도 없으면 진짜 이상(corp_code 불일치·API 변경 등)이라 WARN.
     *
     * <p>회사의 주요정보는 왔는데 이 접수번호 행만 없으면([발행조건확정]·새 정정에 밀린 옛 신고서) 정상이라 DEBUG로 남긴다
     * (2026-10-02 실측: 주요정보는 회사의 최신 신고서 한 건만 준다).
     */
    private void logDetailMissing(DartClient.DealNotice deal, LocalDate today, boolean corpInfoFound) {
        if (corpInfoFound) {
            log.debug("DART 상세는 회사의 최신 신고서 기준 — 이 접수번호 행 없음(정상): corpName={}, rceptNo={}, {}",
                    deal.corpName(), deal.rceptNo(), deal.reportName());
            return;
        }
        LocalDate rceptDt = deal.rceptDt();
        boolean fresh = rceptDt == null || !rceptDt.plusDays(2).isBefore(today);
        if (fresh) {
            log.info("DART 상세 아직 없음(공시 {} — 주요정보 반영 지연, 다음 배치 재시도) — corpName={}, rceptNo={}",
                    rceptDt, deal.corpName(), deal.rceptNo());
        } else {
            log.warn("DART 상세 조회 결과 없음(공시 {} 후 2일 경과) — corpName={}, rceptNo={}",
                    rceptDt, deal.corpName(), deal.rceptNo());
        }
    }

    /** 수동 입력(monitor.IpoController) 직후 오늘 기준으로 상태를 즉시 재계산한다 — 상장일 입력 시 LISTED 반영. */
    public void refreshStatus(IpoDealEntity entity) {
        recalculateStatus(entity, LocalDate.now(clock.withZone(MarketConstants.KST)));
    }

    /** 오늘 날짜와 청약 일정을 비교해 상태를 재계산한다(ADR-9 — listing_date 미제공 한계, IpoStatus 참고). */
    void recalculateStatus(IpoDealEntity entity, LocalDate today) {
        LocalDate start = entity.getSubscriptionStart();
        LocalDate end = entity.getSubscriptionEnd();
        LocalDate listing = entity.getListingDate();

        IpoStatus newStatus;
        if (listing != null && !today.isBefore(listing)) {
            newStatus = IpoStatus.LISTED;
        } else if (start != null && today.isBefore(start)) {
            newStatus = IpoStatus.UPCOMING;
        } else if (start != null && end != null && !today.isBefore(start) && !today.isAfter(end)) {
            newStatus = IpoStatus.SUBSCRIBING;
        } else if (end != null && today.isAfter(end)) {
            newStatus = IpoStatus.PASSED;
        } else {
            newStatus = IpoStatus.UPCOMING; // 일정 미확정 — 기본값 유지
        }
        entity.updateStatus(newStatus, clock.instant());
    }

    /**
     * 기관경쟁률≥임계치 AND 확약률≥임계치 → RECOMMEND, 둘 다 있지만 미충족 → SKIP,
     * 하나라도 없으면 PENDING(ADR-9 ② 필터, IpoRecommendation Javadoc).
     */
    public void evaluateFilter(IpoDealEntity entity) {
        BigDecimal competitionRate = entity.getInstitutionalCompetitionRate();
        BigDecimal lockupRate = entity.getLockupCommitRate();
        BigDecimal minCompetition = filterProperties.minInstitutionalCompetitionRate();
        BigDecimal minLockup = filterProperties.minLockupCommitRate();

        if (competitionRate == null || lockupRate == null) {
            String missing = competitionRate == null && lockupRate == null
                    ? "기관경쟁률·의무보유확약비율"
                    : competitionRate == null ? "기관경쟁률" : "의무보유확약비율";
            String how = entity.getMetricsSource() == IpoDealEntity.MetricsSource.DART
                    ? "수요예측 결과에서 읽지 못함 — 수동 입력 필요(POST /api/ipo/{id}/metrics)"
                    : "[발행조건확정] 수요예측 결과 공시 전 — 공시되면 다음 배치가 자동 입력(수동 입력도 가능)";
            entity.applyRecommendation(IpoRecommendation.PENDING, missing + " 미입력 — " + how, clock.instant());
            return;
        }

        boolean pass = competitionRate.compareTo(minCompetition) >= 0
                && lockupRate.compareTo(minLockup) >= 0;
        String metrics = "기관경쟁률 %s(임계 %s) / 의무보유확약비율 %s(임계 %s, 수량 기준)".formatted(
                IpoDemandForecastCollector.rateText(competitionRate), IpoDemandForecastCollector.rateText(minCompetition),
                IpoDemandForecastCollector.percentText(lockupRate), IpoDemandForecastCollector.percentText(minLockup));
        if (pass) {
            entity.applyRecommendation(IpoRecommendation.RECOMMEND,
                    metrics + " — 둘 다 충족" + sourceNote(entity), clock.instant());
        } else {
            entity.applyRecommendation(IpoRecommendation.SKIP,
                    metrics + " — 임계 미충족" + sourceNote(entity), clock.instant());
        }
    }

    /** 지표 출처 — 화면·알림에서 자동 입력과 수동 입력을 구분해 보여준다. */
    private static String sourceNote(IpoDealEntity entity) {
        if (entity.getMetricsSource() == IpoDealEntity.MetricsSource.DART) {
            return " (DART 수요예측 결과 " + entity.getMetricsRceptNo() + ")";
        }
        return entity.getMetricsSource() == IpoDealEntity.MetricsSource.MANUAL ? " (수동 입력)" : "";
    }

    // ── 알림(2026-10-13 단계 확장·밤 금지·단계별 기록) ─────────────────────────────────────

    /** 지금이 알림 시간대 [07:00, 22:00) KST인가. */
    boolean withinAlertHours() {
        LocalTime now = ZonedDateTime.now(clock.withZone(MarketConstants.KST)).toLocalTime();
        return !now.isBefore(ALERT_FROM) && now.isBefore(ALERT_UNTIL);
    }

    /**
     * 오늘 보낼 알림을 계산해 보내고 기록한다({@link IpoAlertPlanner}). 밤이면 보내지 않고 미룬다.
     *
     * <p>2026-10-02(V16)부터 지켜 온 원칙은 그대로다 — 한 회사가 신고서를 여러 번 내도(정정·발행조건확정) 회사 단위로 한 번,
     * 재기동해도 다시 보내지 않는다. 그 기록을 딜 행의 "마지막 단계" 하나에서 단계별 기록(ipo_alert_log)으로 옮겼다.
     *
     * @return 보낸 알림 수(신규 모음은 한 통)
     */
    private int runAlerts(List<IpoDealEntity> all, LocalDate today) {
        Set<String> corps = all.stream().map(IpoDealEntity::getCorpCode).collect(Collectors.toSet());
        if (corps.isEmpty()) {
            alertsDeferred = false;
            return 0;
        }
        Set<String> sent = alertLog.findByCorpCodeIn(corps).stream().map(IpoAlertLogEntity::key).collect(Collectors.toSet());
        IpoAlertPlanner.Plan plan = IpoAlertPlanner.plan(all, today, sent);
        if (plan.isEmpty()) {
            alertsDeferred = false;
            return 0;
        }
        if (!withinAlertHours()) {
            alertsDeferred = true;
            log.info("공모주 알림 {}건 미룸 — 밤({}~{})에는 보내지 않는다. 07시 이후 매시 점검에서 보낸다",
                    plan.newDeals().size() + plan.alerts().size(), ALERT_QUIET_FROM_TEXT, ALERT_QUIET_UNTIL_TEXT);
            return 0;
        }
        int published = 0;
        if (!plan.newDeals().isEmpty()) {
            publishAlert("공모주 신규", IpoAlertPlanner.PHASE_NEW, plan.newDealsMessage());
            plan.newDeals().forEach(this::record);
            published++;
        }
        for (IpoAlertPlanner.PlannedAlert alert : plan.alerts()) {
            publishAlert(alert.corpName(), alert.phase(), alert.message());
            record(alert);
            published++;
        }
        alertsDeferred = false;
        return published;
    }

    /** DB만 보고 상태를 다시 계산해 알림을 보낸다 — 밤에 미룬 알림, 상장 확인 직후. 외부 호출이 없다. */
    private void alertPass(LocalDate today, String reason) {
        synchronized (batchLock) {
            List<IpoDealEntity> all = repository.findAll();
            Set<IpoDealEntity> changed = new HashSet<>();
            for (IpoDealEntity entity : all) {
                IpoStatus before = entity.getStatus();
                recalculateStatus(entity, today);
                if (entity.getStatus() != before) {
                    changed.add(entity);
                }
            }
            int alerts = runAlerts(all, today);
            for (IpoDealEntity entity : changed) {
                try {
                    repository.save(entity);
                } catch (OptimisticLockingFailureException e) {
                    log.info("공모주 딜 상태 저장 생략(동시 수동 입력) — corpName={}", entity.getCorpName());
                }
            }
            log.info("공모주 알림 점검({}) — 상태 바뀐 딜 {}건, {}", reason, changed.size(),
                    alertsDeferred ? "알림은 밤이라 미룸" : "알림 " + alerts + "건");
        }
    }

    private void record(IpoAlertPlanner.PlannedAlert alert) {
        try {
            alertLog.save(new IpoAlertLogEntity(alert.corpCode(), alert.phase(), alert.key(), clock.instant()));
        } catch (DataIntegrityViolationException e) {
            log.debug("공모주 알림 기록이 이미 있음(동시 실행) — {} {}", alert.corpName(), alert.phase());
        }
    }

    private void publishAlert(String corpName, String phase, String message) {
        publisher.publishEvent(new IpoAlert(corpName, phase, message, clock.instant()));
        log.info("공모주 알림 발행: {} {} — {}", corpName, phase, message);
    }

    // ── 상장 확인(2026-10-13, market.NewListingWatcher) ─────────────────────────────────────

    /**
     * 최근 상장 종목으로 딜의 상장일·종목코드를 채우고, 상태와 알림을 바로 다시 본다(상장 당일 아침 알림).
     * 공모주 기능이 꺼져 있으면({@code dart.enabled=false}) 아무것도 하지 않는다. 실패해도 발행한 쪽(market)에 예외를 넘기지 않는다.
     */
    @EventListener
    public void onRecentListings(RecentListings event) {
        if (!dartProperties.enabled()) {
            return;
        }
        try {
            LocalDate today = LocalDate.now(clock.withZone(MarketConstants.KST));
            int matched;
            synchronized (batchLock) {
                matched = listingMatcher.apply(event.listings(), today);
            }
            if (matched > 0) {
                alertPass(today, "상장 확인 " + matched + "개 회사");
            }
        } catch (RuntimeException e) {
            log.error("공모주 상장 확인 처리 실패 — 다음 거래일 확인 때 다시", e);
        }
    }
}

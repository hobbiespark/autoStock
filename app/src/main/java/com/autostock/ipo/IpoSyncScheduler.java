package com.autostock.ipo;

import com.autostock.common.event.IpoAlert;
import com.autostock.common.util.MarketConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 공모주 일정 수집·필터 판단 배치 (PLAN.md ADR-9 ①②, 트랙 E2) — 평일 08:20 KST(장 시작 전,
 * macrointel.MacroSyncScheduler의 08:30보다 앞선 이유는 이 배치가 딜 상세까지 여러 건 순회
 * 조회해 더 여유를 두기 위함).
 *
 * <p>절차: 최근 {@code dart.lookback-days}일 list.json 조회 → corp_code별 상세(estkRs.json)
 * 조회와 공모 종류 판정 → upsert({@code rcept_no} 기준, 상장사 유상증자는 만들지 않음) → 판정 전 옛 딜 판정 →
 * [발행조건확정] 수요예측 결과 자동 입력 → 상태(IpoStatus) 재계산 → 필터 평가(IpoRecommendation) →
 * 청약 시작 D-1/당일 알림 대상에 {@link IpoAlert} 발행.
 *
 * <p>2026-10-02(aiDoc/ipo-demand-forecast.md): 기관경쟁률·의무보유확약비율을 [발행조건확정] 원본에서 자동으로 채우고
 * ({@link IpoDemandForecastCollector}), 상장사 유상증자가 공모주 딜로 들어오던 결함을 고쳤다({@link IpoOfferingClassifier}).
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
    static final String PHASE_D_MINUS_1 = "D-1";
    static final String PHASE_START = "START";

    private final DartProperties dartProperties;
    private final IpoFilterProperties filterProperties;
    private final DartClient dartClient;
    private final IpoDealRepository repository;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private final IpoOfferingClassifier classifier;
    private final IpoDemandForecastCollector forecastCollector;

    public IpoSyncScheduler(DartProperties dartProperties, IpoFilterProperties filterProperties,
                             DartClient dartClient, IpoDealRepository repository,
                             ApplicationEventPublisher publisher, Clock clock) {
        this.dartProperties = dartProperties;
        this.filterProperties = filterProperties;
        this.dartClient = dartClient;
        this.repository = repository;
        this.publisher = publisher;
        this.clock = clock;
        // 같은 외부 호출(DartClient)·저장소를 쓰는 배치의 단계들이라 빈으로 나누지 않고 여기서 만든다(생성자·테스트 구성 유지)
        this.classifier = new IpoOfferingClassifier(dartClient, repository, clock);
        this.forecastCollector = new IpoDemandForecastCollector(dartClient, repository, clock);
    }

    /** 매 평일 08:20 KST 1회 실행. */
    @Scheduled(cron = "0 20 8 * * MON-FRI", zone = "Asia/Seoul")
    public void syncScheduled() {
        syncNow();
    }

    /** 마지막으로 DART 조회까지 끝낸 날(KST) — 따라잡기 판단 기준. */
    private volatile LocalDate lastSuccessDate;

    // ── 따라잡기(catch-up) — 2026-09-18 운영 교훈 ─────────────────────────────────────
    // 배치가 cron 시각에만 돌면 (1) 낮에 재기동한 날은 그날 내내 데이터가 없고 (2) 그 시각에 API가 잠깐
    // 실패하면 다음 날까지 복구 기회가 없다. 그래서 ① 기동 직후 1회, ② 매시 05분에 "오늘 성공 기록이
    // 없으면" 다시 시도한다. 성공한 날은 시간별 점검이 아무 일도 하지 않으므로 외부 API 부하는 하루 1회 그대로다.
    // 키가 비어 있으면(CI 등) 시도 자체를 건너뛴다 — 외부 호출로 테스트가 느려지거나 실패하는 일을 막는다.

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
            log.info(summary.toLogLine());
        }
    }

    /** 수집 본체 — 비활성이면 null, 끝까지 돌면 요약을 돌려준다. 목록 조회 실패는 예외로 올라간다(따라잡기가 재시도). */
    SyncSummary sync() {
        if (!dartProperties.enabled()) {
            log.info("ipo 수집 비활성(dart.enabled=false) — 이번 배치 스킵");
            return null;
        }
        LocalDate today = LocalDate.now(clock.withZone(MarketConstants.KST));
        LocalDate since = today.minusDays(dartProperties.lookbackDays());

        List<DartClient.DealNotice> deals = dartClient.fetchRecentEquityFilings(since, today);
        lastSuccessDate = today; // 목록 조회가 예외 없이 끝났으면 오늘 수집으로 인정(개별 딜 실패는 아래서 격리)
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

        // V11 이전에 들어온 딜 등 공모 종류를 모르는 딜을 조금씩 판정하고, 수요예측 지표를 채운다 — 단계마다 실패를 격리한다
        int classified = runStep("공모 종류 판정", () -> classifier.backfill(today, lookedUpCorps));
        int metricsFilled = runStep("수요예측 지표 자동 입력", () -> forecastCollector.collect(today));

        // 상태·알림 재계산은 이번 배치에서 새로 잡히지 않은 기존 딜(예: list.json lookback
        // 창 밖으로 밀려난 딜)도 포함해야 하므로 전체를 다시 훑는다.
        List<IpoDealEntity> all = repository.findAll();
        for (IpoDealEntity entity : all) {
            recalculateStatus(entity, today);
            if (!entity.isRightsOffering()) { // 상장사 유상증자는 권고·알림 대상이 아니다
                evaluateFilter(entity);
                maybeAlert(entity, today);
            }
            try {
                repository.save(entity);
            } catch (OptimisticLockingFailureException e) {
                // 조회 뒤 수동 입력(IpoDealCommandService)이 먼저 저장됐다 — 그 입력을 덮지 않고 이번엔
                // 건너뛴다. 상태·필터는 다음 배치(또는 입력 직후 재평가)에서 다시 맞춰진다.
                log.info("공모주 딜 재계산 저장 생략(동시 수동 입력) — corpName={}", entity.getCorpName());
            }
        }
        return new SyncSummary(since, today, deals.size(), created, excluded, skipped, failed, classified, metricsFilled,
                all.size());
    }

    private int runStep(String name, java.util.function.IntSupplier step) {
        try {
            return step.getAsInt();
        } catch (RuntimeException e) {
            log.error("공모주 배치 단계 실패({}) — 다음 배치에서 다시 시도", name, e);
            return 0;
        }
    }

    /**
     * 수집 요약(2026-10-01 로그 점검 F-4, 2026-10-02 유상증자 제외·지표 자동 입력 추가).
     *
     * @param filings       조회 기간의 증권신고(지분증권) 건수
     * @param created       이번에 새로 만든 딜 수
     * @param excluded      상장사 유상증자라 딜로 만들지 않은 건수
     * @param skipped       동시 수동 입력으로 저장을 건너뛴 건수(다음 배치 재시도)
     * @param failed        개별 동기화 실패 건수
     * @param classified    공모 종류를 이번에 판정한 옛 딜 수
     * @param metricsFilled 수요예측 지표를 자동 입력한 회사 수
     * @param totalDeals    상태·필터·알림을 다시 계산한 전체 딜 수
     */
    record SyncSummary(LocalDate since, LocalDate until, int filings, int created, int excluded, int skipped, int failed,
                       int classified, int metricsFilled, int totalDeals) {

        String toLogLine() {
            return ("공모주 수집 완료 — %s~%s 증권신고(지분증권) %d건(신규 딜 %d·유상증자 제외 %d·저장 생략 %d·실패 %d), "
                    + "옛 딜 공모 종류 판정 %d건, 수요예측 지표 자동 입력 %d개 회사, 전체 딜 %d건 상태 재계산")
                    .formatted(since, until, filings, created, excluded, skipped, failed, classified, metricsFilled,
                            totalDeals);
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

    /** 청약 시작 D-1·당일에만 알림을 발행한다(ADR-9 ③, 반복 알림으로 스팸이 되지 않도록). */
    private void maybeAlert(IpoDealEntity entity, LocalDate today) {
        LocalDate start = entity.getSubscriptionStart();
        if (start == null) {
            return;
        }
        if (start.minusDays(1).isEqual(today)) {
            publishAlert(entity, PHASE_D_MINUS_1,
                    "[청약 D-1] %s — 공모가 %s, 주관사 %s, 권고: %s (%s)".formatted(
                            entity.getCorpName(), formatPrice(entity), nullToDash(entity.getLeadManager()),
                            entity.getRecommendation(), entity.getRecommendReason()));
        } else if (start.isEqual(today)) {
            publishAlert(entity, PHASE_START,
                    "[청약 시작] %s — 청약기간 %s~%s, 권고: %s (%s). 청약 실행은 영웅문S#에서 수동.".formatted(
                            entity.getCorpName(), start, entity.getSubscriptionEnd(),
                            entity.getRecommendation(), entity.getRecommendReason()));
        }
    }

    private void publishAlert(IpoDealEntity entity, String phase, String message) {
        publisher.publishEvent(new IpoAlert(entity.getCorpName(), phase, message, clock.instant()));
        log.info("공모주 알림 발행: {} {} — {}", entity.getCorpName(), phase, message);
    }

    private static String formatPrice(IpoDealEntity entity) {
        return entity.getOfferPriceConfirmed() != null ? entity.getOfferPriceConfirmed() + "원" : "미확정";
    }

    private static String nullToDash(String s) {
        return s != null ? s : "-";
    }
}

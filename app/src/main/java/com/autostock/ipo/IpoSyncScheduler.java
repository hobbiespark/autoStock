package com.autostock.ipo;

import com.autostock.common.event.IpoAlert;
import com.autostock.common.event.ListingDayPrices;
import com.autostock.common.event.ListingOpenPrices;
import com.autostock.common.event.RecentListings;
import com.autostock.common.util.MarketConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>2026-10-15(사용자 "공모주는 청약 기준 추천 전략이 필요", aiDoc/ipo-subscription-strategy.md):
 * <ul>
 *   <li>권고를 고정 임계(기관경쟁률 500:1·확약 20%)에서 <b>청약 추천</b>({@link IpoSubscriptionAdvisor} — 비례까지·균등만·패스)으로
 *       바꿨다. 권고 칸은 RECOMMEND(비례까지·균등만)/SKIP(패스)/PENDING 그대로 두고, 등급·점수·근거를 따로 남긴다.</li>
 *   <li>시장 맥락(직전 20건 확약 중앙값, 직전 상장 5곳 상장일 종가 평균)은 {@code ipo_listing_history}(V19)에서 센다. 수요예측 결과가
 *       나온 딜의 재료를 그 표에 남기고, 상장일 시세({@link ListingDayPrices}, market 모듈 15:40)도 남긴다.</li>
 *   <li>평일 18:30에 한 번 더 수집한다 — [발행조건확정]은 보통 오후에 나오고 청약은 다음 날 시작해, 추천을 전날 저녁에 받게 한다.</li>
 * </ul>
 *
 * <p>2026-10-30(사용자 공모주 알림 강화, aiDoc/ipo-automation.md "2026-10-30 바뀜"): 스팩을 별도 등급(SPAC)으로 청약 일정 알림에
 * 넣고, 마감일·상장일 08:30 아침 알림, 상장일 09:05 시초가 알림({@link ListingOpenPrices}), 월요일 08:00 주간 요약, 청약 금액 추천
 * ({@link IpoSubscriptionSizer}, {@code ipo.subscription.*})을 더했다. 매시 점검은 알림 시간대 안이면 DB만 보고 알림을 다시 계산한다
 * (08:30 알림을 앱이 꺼져 놓쳤을 때 다음 매시 점검이 보낸다).
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
    /** 판단 재료·상장일 성과(V19) — 시험용 7인자 생성자로 만들면 null(맥락 없이 판단, 기록 안 함). */
    private final IpoListingHistoryRepository history;
    /** 청약 금액 추천(2026-10-30). */
    private final IpoSubscriptionSizer sizer;

    /** 수집·알림·상장 확인이 같은 딜을 동시에 고치지 않게 — 정기 배치·매시 점검·상장 확인 이벤트가 겹칠 수 있다. */
    private final Object batchLock = new Object();

    /**
     * 이미 "유상증자 — 딜로 만들지 않음"을 INFO로 남긴 접수번호. 유상증자는 딜을 만들지 않아 조회 기간 동안 배치마다 다시 판정되고,
     * 같은 줄이 배치마다 반복됐다(10/15 07:53·08:20 각 4줄). 앱이 켜져 있는 동안 한 번만 INFO, 그 뒤는 DEBUG.
     */
    private final Set<String> rightsLogged = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 시험용 — 판단 재료 표 없이(맥락 없음). */
    public IpoSyncScheduler(DartProperties dartProperties, IpoFilterProperties filterProperties,
                     DartClient dartClient, IpoDealRepository repository, IpoAlertLogRepository alertLog,
                     ApplicationEventPublisher publisher, Clock clock) {
        this(dartProperties, filterProperties, dartClient, repository, alertLog, null, publisher, clock);
    }

    /** 시험용 — 청약 금액 추천 기본값(예산 200만 원·증거금률 50%·최소 10주·10주 단위). */
    public IpoSyncScheduler(DartProperties dartProperties, IpoFilterProperties filterProperties,
                            DartClient dartClient, IpoDealRepository repository, IpoAlertLogRepository alertLog,
                            IpoListingHistoryRepository history, ApplicationEventPublisher publisher, Clock clock) {
        this(dartProperties, filterProperties, dartClient, repository, alertLog, history, IpoSubscriptionProperties.DEFAULT,
                publisher, clock);
    }

    /**
     * @param filterProperties 2026-10-15부터 권고에 쓰지 않는다(청약 추천으로 바뀜) — 설정 호환을 위해 받기만 한다
     * @param subscriptionProperties 청약 금액 추천 설정(2026-10-30, {@code ipo.subscription.*})
     */
    @Autowired
    public IpoSyncScheduler(DartProperties dartProperties, IpoFilterProperties filterProperties,
                             DartClient dartClient, IpoDealRepository repository, IpoAlertLogRepository alertLog,
                             IpoListingHistoryRepository history, IpoSubscriptionProperties subscriptionProperties,
                             ApplicationEventPublisher publisher, Clock clock) {
        this.sizer = new IpoSubscriptionSizer(subscriptionProperties);
        this.history = history;
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

    /**
     * 평일 18:30 KST — 그날 오후에 나온 [발행조건확정]을 읽어 청약 추천을 전날 저녁에 보낸다(2026-10-15). 디티에스: 확정 10/12(월) 저녁,
     * 청약 10/13~14 — 아침 수집만으로는 청약 첫날 아침에야 추천이 나간다. 알림 시간대(07~22시) 안이다.
     */
    @Scheduled(cron = "0 30 18 * * MON-FRI", zone = "Asia/Seoul")
    public void syncEvening() {
        syncNow();
    }

    /**
     * 매일 08:30 KST — 청약 마감일 "오늘 마감" 재알림(END-AM)과 상장일 매도 안내(LISTING-AM)(2026-10-30). DB만 보고 외부 호출은 없다.
     * 휴일에도 돈다(달력 기준 — D-1과 같은 관례). 해당 딜이 없으면 아무것도 보내지 않는다.
     */
    @Scheduled(cron = "0 30 8 * * *", zone = "Asia/Seoul")
    public void morningAlerts() {
        if (!dartProperties.enabled()) {
            return;
        }
        try {
            alertPass(LocalDate.now(clock.withZone(MarketConstants.KST)), "아침 알림 08:30", true);
        } catch (RuntimeException e) {
            log.error("공모주 아침 알림 실패 — 다음 시간별 점검에서 다시", e);
        }
    }

    /**
     * 월요일 08:00 KST — 이번 주 청약·상장 일정 요약 한 통(2026-10-30). 휴장일이어도 보낸다(일정 안내라 거래일 판정과 무관 — D-1처럼
     * 달력 기준). 놓치면 그날 매시 점검이 보낸다(주마다 한 번, {@code ipo_alert_log}).
     */
    @Scheduled(cron = "0 0 8 * * MON", zone = "Asia/Seoul")
    public void weeklySummary() {
        if (!dartProperties.enabled()) {
            return;
        }
        try {
            weeklyPass(LocalDate.now(clock.withZone(MarketConstants.KST)));
        } catch (RuntimeException e) {
            log.error("공모주 주간 요약 실패 — 다음 시간별 점검에서 다시", e);
        }
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
            if (withinAlertHours()) {
                // 밤에 미룬 알림, 그리고 시각이 정해진 알림(08:30 아침·월요일 08:00 주간)을 그 시각에 놓쳤을 때 — DB만 본다(2026-10-30)
                try {
                    alertPass(today, alertsDeferred ? "밤에 미룬 알림" : "시간별 점검", !alertsDeferred);
                    weeklyPass(today);
                } catch (RuntimeException e) {
                    log.error("ipo 알림 점검 실패 — 다음 시간별 점검에서 재시도", e);
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
        List<IpoListingHistoryEntity> past = runStep("판단 재료 기록", () -> recordInputs(all, today), List.of());
        for (IpoDealEntity entity : all) {
            recalculateStatus(entity, today);
            if (!entity.isRightsOffering()) { // 상장사 유상증자는 권고·알림 대상이 아니다
                evaluate(entity, past, today);
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
                created, excluded, skipped, failed, classified, filled.metricsFilled(), filled.pricesFilled(),
                filled.floatsFilled(), all.size(), alerts, alertsDeferred);
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
     * @param floatsFilled  상장 직후 유통가능물량을 자동 입력한 회사 수(2026-10-15)
     * @param totalDeals    상태·필터·알림을 다시 계산한 전체 딜 수
     * @param alerts        이번에 보낸 알림 수(신규 모음은 한 통)
     * @param alertsDeferred 밤이라 알림을 미뤘는가
     */
    record SyncSummary(LocalDate since, LocalDate until, boolean complete, int pagesRead, int totalPages, int filings,
                       int created, int excluded, int skipped, int failed, int classified, int metricsFilled,
                       int pricesFilled, int floatsFilled, int totalDeals, int alerts, boolean alertsDeferred) {

        String toLogLine() {
            String head = complete ? "공모주 수집 완료" : "공모주 수집 미완료(공시 목록을 끝까지 읽지 못함 — 다음 시간별 점검에서 다시)";
            String alertText = alertsDeferred ? "알림은 밤이라 미룸(07시 이후 보냄)" : "알림 %d건".formatted(alerts);
            return ("%s — %s~%s 증권신고(지분증권) %d건(목록 %d/%d쪽, 신규 딜 %d·유상증자 제외 %d·저장 생략 %d·실패 %d), "
                    + "옛 딜 공모 종류 판정 %d건, 자동 입력 수요예측 지표 %d·확정 공모가 %d·유통가능물량 %d개 회사, 전체 딜 %d건 상태 재계산, %s")
                    .formatted(head, since, until, filings, pagesRead, totalPages, created, excluded, skipped, failed,
                            classified, metricsFilled, pricesFilled, floatsFilled, totalDeals, alertText);
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
            String method = lookup.map(DartClient.OfferingLookup::offeringMethod).orElse("확인 못 함");
            if (rightsLogged.add(deal.rceptNo())) {
                log.info("상장사 유상증자 — 공모주 딜로 만들지 않음: {} {} (rceptNo={}, 모집방법 {})", deal.corpName(),
                        deal.reportName(), deal.rceptNo(), method);
            } else {
                log.debug("상장사 유상증자 — 공모주 딜로 만들지 않음(이미 남김): {} rceptNo={}", deal.corpName(), deal.rceptNo());
            }
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
     * 청약 추천을 매긴다({@link IpoSubscriptionAdvisor}, 2026-10-15) — 수동 입력 직후(IpoDealCommandService)에도 부른다.
     * 기관경쟁률이 없으면 판단 보류(PENDING). 확약이 없어도 판단한다(확약 항목만 0점).
     */
    public void evaluateFilter(IpoDealEntity entity) {
        LocalDate today = LocalDate.now(clock.withZone(MarketConstants.KST));
        evaluate(entity, history == null ? List.of() : history.findAll(), today);
    }

    void evaluate(IpoDealEntity entity, List<IpoListingHistoryEntity> past, LocalDate today) {
        if (isSpac(entity.getCorpName())) {
            // 2026-10-16 로그: 스팩(공모가 2,000원)이 "균등만(+2)"을 받았다. 전략 검증(85곳)에서 스팩은 뺐다 — 점수는 매기지 않는다.
            // 2026-10-30 사용자 "스팩도 청약": 패스에서 빼고 별도 등급(SPAC)으로 청약 일정 알림 대상(관심 딜)에 넣는다.
            entity.applyRecommendation(IpoSubscriptionAdvisor.Grade.SPAC.recommendation(), SPAC_REASON + sourceNote(entity),
                    clock.instant());
            entity.applyAdvice(IpoSubscriptionAdvisor.Grade.SPAC.name(), null, clock.instant());
            return;
        }
        BigDecimal competitionRate = entity.getInstitutionalCompetitionRate();
        if (competitionRate == null) {
            String how = entity.getMetricsSource() == IpoDealEntity.MetricsSource.DART
                    ? "수요예측 결과에서 읽지 못함 — 수동 입력 필요(POST /api/ipo/{id}/metrics)"
                    : "[발행조건확정] 수요예측 결과 공시 전 — 공시되면 다음 배치가 자동 입력(수동 입력도 가능)";
            String missing = entity.getLockupCommitRate() == null ? "기관경쟁률·의무보유확약비율" : "기관경쟁률";
            entity.applyRecommendation(IpoRecommendation.PENDING, missing + " 미입력 — " + how, clock.instant());
            entity.applyAdvice(IpoSubscriptionAdvisor.Grade.PENDING.name(), null, clock.instant());
            return;
        }
        IpoSubscriptionAdvisor.Advice advice = IpoSubscriptionAdvisor.advise(new IpoSubscriptionAdvisor.Input(
                competitionRate, entity.getLockupCommitRate(), entity.getOfferPriceFinal(), entity.getOfferPriceLow(),
                entity.getOfferPriceHigh(), entity.getFloatRatio(), entity.getListedShares(),
                context(past, decisionDate(entity, today), entity.getCorpCode())));
        entity.applyRecommendation(advice.grade().recommendation(), advice.reason() + sourceNote(entity), clock.instant());
        entity.applyAdvice(advice.grade().name(), advice.score(), clock.instant());
    }

    /**
     * 스팩 사유 — 2026-10-30부터 청약 일정 알림 대상이다(예전 문구 "패스 — 스팩(기업인수목적회사): 청약 추천 대상 아님"). 점수는 매기지
     * 않는다(전략 검증에서 제외). 청약 금액은 {@link IpoSubscriptionSizer}가 알림에 붙인다.
     */
    static final String SPAC_REASON = "스팩 — 기업인수목적회사: 점수 없이 청약 일정 알림(전략 검증 제외, 공모가 2,000원 고정). "
            + "청약: 겹치는 추천 딜 몫을 뺀 남은 예산 안에서 비례";

    /** 스팩(기업인수목적회사) — 이름으로 가린다(상장일 시세 기록의 제외 규칙과 같은 말). */
    static boolean isSpac(String corpName) {
        return corpName != null && (corpName.contains("기업인수목적") || corpName.contains("스팩"));
    }

    /** 판단 시점 — [발행조건확정] 접수일(맥락이 그날에 고정돼 등급이 날마다 흔들리지 않는다). 수동 입력이면 오늘. */
    static LocalDate decisionDate(IpoDealEntity entity, LocalDate today) {
        return IpoOfferingClassifier.rceptDate(entity.getMetricsRceptNo()).orElse(today);
    }

    /** 판단 시점 앞의 자료로 센 맥락 — 확약은 그 전에 확정 신고서가 나온 곳, 분위기는 그 전에 상장한 곳. 자기 회사는 뺀다. */
    static IpoSubscriptionAdvisor.Context context(List<IpoListingHistoryEntity> past, LocalDate decisionDate, String corpCode) {
        List<BigDecimal> lockups = past.stream()
                .filter(h -> !h.getCorpCode().equals(corpCode) && h.getLockupRate() != null && h.getConfirmedOn() != null
                        && h.getConfirmedOn().isBefore(decisionDate))
                .sorted(Comparator.comparing(IpoListingHistoryEntity::getConfirmedOn))
                .map(IpoListingHistoryEntity::getLockupRate).toList();
        List<BigDecimal> moods = past.stream()
                .filter(h -> !h.getCorpCode().equals(corpCode) && h.getListingDate() != null
                        && h.getListingDate().isBefore(decisionDate) && h.closeReturn() != null)
                .sorted(Comparator.comparing(IpoListingHistoryEntity::getListingDate))
                .map(IpoListingHistoryEntity::closeReturn).toList();
        return IpoSubscriptionAdvisor.Context.of(lockups, moods);
    }

    /**
     * 수요예측 결과가 있는 공모주 회사마다 판단 재료를 {@code ipo_listing_history}에 남기고, 표 전체를 돌려준다(맥락 계산용).
     * 회사의 대표 딜 = 지표가 있는 딜 중 가장 최근 신고서.
     */
    private List<IpoListingHistoryEntity> recordInputs(List<IpoDealEntity> all, LocalDate today) {
        if (history == null) {
            return List.of();
        }
        Map<String, IpoListingHistoryEntity> rows = new LinkedHashMap<>();
        history.findAll().forEach(h -> rows.put(h.getCorpCode(), h));
        Map<String, IpoDealEntity> representative = new LinkedHashMap<>();
        for (IpoDealEntity deal : all) {
            if (deal.isRightsOffering() || deal.getOfferingKind() != OfferingKind.IPO || deal.getInstitutionalCompetitionRate() == null
                    || isSpac(deal.getCorpName())) {
                continue;   // 스팩은 판단 재료(확약 중앙값·분위기)를 흐리므로 남기지 않는다
            }
            representative.merge(deal.getCorpCode(), deal,
                    (a, b) -> a.getRceptNo().compareTo(b.getRceptNo()) >= 0 ? a : b);
        }
        for (IpoDealEntity deal : representative.values()) {
            IpoListingHistoryEntity row = rows.computeIfAbsent(deal.getCorpCode(),
                    code -> new IpoListingHistoryEntity(code, deal.getCorpName(), clock.instant()));
            row.applyInputs(deal.getCorpName(), decisionDate(deal, today), deal.getInstitutionalCompetitionRate(),
                    deal.getLockupCommitRate(), deal.getOfferPriceFinal(), deal.getOfferPriceLow(), deal.getOfferPriceHigh(),
                    deal.getFloatRatio(), deal.getAdviceGrade(), deal.getAdviceScore(), clock.instant());
            history.save(row);
        }
        return List.copyOf(rows.values());
    }

    /**
     * 상장일 시세(market 모듈 15:40, 키움 일봉) — 종목코드로 맞춘 공모주 딜에 시가·종가를 남기고 판단 재료 표에도 적는다.
     * 다음 공모주의 "시장 분위기"(직전 상장 5곳 상장일 종가 평균)가 이 값으로 움직인다.
     */
    @EventListener
    public void onListingDayPrices(ListingDayPrices event) {
        if (!dartProperties.enabled() || history == null) {
            return;
        }
        try {
            synchronized (batchLock) {
                recordListingPrices(event);
            }
        } catch (RuntimeException e) {
            log.error("공모주 상장일 시세 기록 실패 — 다음 거래일 15:40에 다시", e);
        }
    }

    private void recordListingPrices(ListingDayPrices event) {
        List<IpoDealEntity> all = repository.findAll();
        for (ListingDayPrices.Entry entry : event.entries()) {
            List<IpoDealEntity> deals = all.stream()
                    .filter(d -> entry.stockCode().equals(d.getStockCode()) && !d.isRightsOffering()
                            && !isSpac(d.getCorpName())).toList();
            if (deals.isEmpty()) {
                continue;   // 공모주 딜과 맞추지 못한 종목(이전상장·스팩 등)
            }
            IpoDealEntity rep = deals.stream().max(Comparator.comparing(IpoDealEntity::getRceptNo)).orElseThrow();
            BigDecimal offer = deals.stream().map(IpoDealEntity::getOfferPriceFinal).filter(java.util.Objects::nonNull)
                    .findFirst().orElse(rep.bestKnownOfferPrice());
            for (IpoDealEntity deal : deals) {
                if (deal.applyListingPrices(entry.open(), entry.close(), clock.instant())) {
                    try {
                        repository.save(deal);
                    } catch (OptimisticLockingFailureException e) {
                        log.info("상장일 시세 저장 생략(동시 수동 입력) — {}", deal.getCorpName());
                    }
                }
            }
            IpoListingHistoryEntity row = history.findById(rep.getCorpCode())
                    .orElseGet(() -> new IpoListingHistoryEntity(rep.getCorpCode(), rep.getCorpName(), clock.instant()));
            row.applyListing(entry.stockCode(), entry.listedOn(), entry.open(), entry.close(), offer, clock.instant());
            history.save(row);
            log.info("공모주 상장일 성과 기록: {}({}) {} 상장 — 청약 추천 {} · 공모가 {} → 시가 {} · 종가 {}", rep.getCorpName(),
                    entry.stockCode(), entry.listedOn(), adviceLabel(row.getAdviceGrade(), row.getAdviceScore()),
                    offer == null ? "미확인" : IpoAlertPlanner.won(offer), change(entry.open(), offer), change(entry.close(), offer));
        }
    }

    private static String adviceLabel(String grade, Integer score) {
        if (grade == null) {
            return "없음";
        }
        String label = IpoSubscriptionAdvisor.Grade.valueOf(grade).label;
        return score == null ? label : "%s(%+d)".formatted(label, score);
    }

    private static String change(BigDecimal price, BigDecimal offer) {
        if (price == null || offer == null || offer.signum() <= 0) {
            return price == null ? "-" : IpoAlertPlanner.won(price);
        }
        BigDecimal pct = price.divide(offer, 6, java.math.RoundingMode.HALF_UP).subtract(BigDecimal.ONE)
                .multiply(BigDecimal.valueOf(100)).setScale(1, java.math.RoundingMode.HALF_UP);
        return IpoAlertPlanner.won(price) + "(" + (pct.signum() > 0 ? "+" : "") + pct.toPlainString() + "%)";
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
        IpoAlertPlanner.Plan plan = IpoAlertPlanner.plan(all, today, nowKst(), sent, sizer);
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

    private LocalTime nowKst() {
        return ZonedDateTime.now(clock.withZone(MarketConstants.KST)).toLocalTime();
    }

    private void alertPass(LocalDate today, String reason) {
        alertPass(today, reason, false);
    }

    /**
     * DB만 보고 상태를 다시 계산해 알림을 보낸다 — 밤에 미룬 알림, 상장 확인 직후, 08:30 아침 알림, 매시 점검. 외부 호출이 없다.
     *
     * @param quiet 바뀐 것도 보낸 것도 없으면 INFO 로그를 남기지 않는다(매시 점검이 시간마다 같은 줄을 쌓지 않게)
     */
    private void alertPass(LocalDate today, String reason, boolean quiet) {
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
            if (quiet && changed.isEmpty() && alerts == 0 && !alertsDeferred) {
                log.debug("공모주 알림 점검({}) — 바뀐 것 없음", reason);
                return;
            }
            log.info("공모주 알림 점검({}) — 상태 바뀐 딜 {}건, {}", reason, changed.size(),
                    alertsDeferred ? "알림은 밤이라 미룸" : "알림 " + alerts + "건");
        }
    }

    /** 주간 요약(월요일 08:00 이후 한 번) — 시간대 밖이면 보내지 않는다(다음 매시 점검이 다시 본다). */
    private void weeklyPass(LocalDate today) {
        if (!withinAlertHours()) {
            return;
        }
        synchronized (batchLock) {
            Set<String> sent = alertLog.findByCorpCodeIn(Set.of(IpoAlertPlanner.WEEKLY_CORP)).stream()
                    .map(IpoAlertLogEntity::key).collect(Collectors.toSet());
            IpoAlertPlanner.weekly(repository.findAll(), today, nowKst(), sent, sizer).ifPresent(alert -> {
                publishAlert(alert.corpName(), alert.phase(), alert.message());
                record(alert);
            });
        }
    }

    /**
     * 상장일 시초가(market 모듈 09:05) — 오늘 상장한 관심 딜에 시초가와 공모가 대비 수익률을 알린다(2026-10-30). 실패해도 발행한
     * 쪽(market)에 예외를 넘기지 않는다.
     */
    @EventListener
    public void onListingOpenPrices(ListingOpenPrices event) {
        if (!dartProperties.enabled()) {
            return;
        }
        try {
            LocalDate today = LocalDate.now(clock.withZone(MarketConstants.KST));
            if (!today.equals(event.day()) || !withinAlertHours()) {
                return;
            }
            Map<String, BigDecimal> opens = new LinkedHashMap<>();
            event.entries().forEach(e -> opens.put(e.stockCode(), e.open()));
            synchronized (batchLock) {
                List<IpoDealEntity> all = repository.findAll();
                Set<String> corps = all.stream().map(IpoDealEntity::getCorpCode).collect(Collectors.toSet());
                Set<String> sent = corps.isEmpty() ? Set.of() : alertLog.findByCorpCodeIn(corps).stream()
                        .map(IpoAlertLogEntity::key).collect(Collectors.toSet());
                for (IpoAlertPlanner.PlannedAlert alert : IpoAlertPlanner.openingAlerts(all, today, sent, opens)) {
                    publishAlert(alert.corpName(), alert.phase(), alert.message());
                    record(alert);
                }
            }
        } catch (RuntimeException e) {
            log.error("공모주 상장일 시초가 알림 실패", e);
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

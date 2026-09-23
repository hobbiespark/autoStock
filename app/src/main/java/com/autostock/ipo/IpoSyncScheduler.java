package com.autostock.ipo;

import com.autostock.common.event.IpoAlert;
import com.autostock.common.util.MarketConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 공모주 일정 수집·필터 판단 배치 (PLAN.md ADR-9 ①②, 트랙 E2) — 평일 08:20 KST(장 시작 전,
 * macrointel.MacroSyncScheduler의 08:30보다 앞선 이유는 이 배치가 딜 상세까지 여러 건 순회
 * 조회해 더 여유를 두기 위함).
 *
 * <p>절차: 최근 {@code dart.lookback-days}일 list.json 조회 → corp_code별 상세(estkRs.json)
 * 조회 → upsert({@code rcept_no} 기준) → 상태(IpoStatus) 재계산 → 필터 평가
 * (IpoRecommendation) → 청약 시작 D-1/당일 알림 대상에 {@link IpoAlert} 발행.
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

    public IpoSyncScheduler(DartProperties dartProperties, IpoFilterProperties filterProperties,
                             DartClient dartClient, IpoDealRepository repository,
                             ApplicationEventPublisher publisher, Clock clock) {
        this.dartProperties = dartProperties;
        this.filterProperties = filterProperties;
        this.dartClient = dartClient;
        this.repository = repository;
        this.publisher = publisher;
        this.clock = clock;
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
        if (!dartProperties.enabled()) {
            log.info("ipo 수집 비활성(dart.enabled=false) — 이번 배치 스킵");
            return;
        }
        LocalDate today = LocalDate.now(clock.withZone(MarketConstants.KST));
        LocalDate since = today.minusDays(dartProperties.lookbackDays());

        List<DartClient.DealNotice> deals = dartClient.fetchRecentEquityFilings(since, today);
        lastSuccessDate = today; // 목록 조회가 예외 없이 끝났으면 오늘 수집으로 인정(개별 딜 실패는 아래서 격리)
        for (DartClient.DealNotice deal : deals) {
            try {
                syncOneDeal(deal, since, today);
            } catch (RuntimeException e) {
                log.error("공모주 딜 동기화 실패(스킵) — corpName={}, rceptNo={}",
                        deal.corpName(), deal.rceptNo(), e);
            }
        }

        // 상태·알림 재계산은 이번 배치에서 새로 잡히지 않은 기존 딜(예: list.json lookback
        // 창 밖으로 밀려난 딜)도 포함해야 하므로 전체를 다시 훑는다.
        List<IpoDealEntity> all = repository.findAll();
        for (IpoDealEntity entity : all) {
            recalculateStatus(entity, today);
            evaluateFilter(entity);
            maybeAlert(entity, today);
        }
        repository.saveAll(all);
    }

    private void syncOneDeal(DartClient.DealNotice deal, LocalDate since, LocalDate today) {
        IpoDealEntity entity = repository.findByRceptNo(deal.rceptNo())
                .orElseGet(() -> new IpoDealEntity(deal.corpCode(), deal.corpName(), deal.rceptNo(), SOURCE_DART));
        dartClient.fetchOfferingDetail(deal.corpCode(), deal.rceptNo(), since, today)
                .ifPresentOrElse(
                        entity::applyOfferingDetail,
                        () -> log.warn("DART 상세 조회 결과 없음 — corpName={}, rceptNo={}",
                                deal.corpName(), deal.rceptNo()));
        repository.save(entity);
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
        entity.updateStatus(newStatus);
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
            entity.applyRecommendation(IpoRecommendation.PENDING,
                    missing + " 미입력 — 자동 수집 불가(DART 미제공), POST /api/ipo/{id}/metrics로 수동 입력 필요");
            return;
        }

        boolean pass = competitionRate.compareTo(minCompetition) >= 0
                && lockupRate.compareTo(minLockup) >= 0;
        if (pass) {
            entity.applyRecommendation(IpoRecommendation.RECOMMEND,
                    "기관경쟁률 %s:1 ≥ %s:1 AND 확약률 %s ≥ %s 충족".formatted(
                            competitionRate, minCompetition, lockupRate, minLockup));
        } else {
            entity.applyRecommendation(IpoRecommendation.SKIP,
                    "기관경쟁률 %s:1(임계 %s:1) / 확약률 %s(임계 %s) — 임계 미충족".formatted(
                            competitionRate, minCompetition, lockupRate, minLockup));
        }
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
        publisher.publishEvent(new IpoAlert(entity.getCorpName(), phase, message, Instant.now()));
        log.info("공모주 알림 발행: {} {} — {}", entity.getCorpName(), phase, message);
    }

    private static String formatPrice(IpoDealEntity entity) {
        return entity.getOfferPriceConfirmed() != null ? entity.getOfferPriceConfirmed() + "원" : "미확정";
    }

    private static String nullToDash(String s) {
        return s != null ? s : "-";
    }
}

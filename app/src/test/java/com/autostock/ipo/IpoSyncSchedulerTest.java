package com.autostock.ipo;

import java.time.Instant;
import com.autostock.common.event.IpoAlert;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IpoSyncScheduler — 필터 판정(ADR-9 ②), 상태 재계산(IpoStatus), D-1/당일 알림 발행,
 * rcept_no 기준 upsert를 dart.enabled=true/false 조합으로 검증한다. 실제 네트워크 호출은
 * 하지 않는다(DartClient는 mock).
 */
class IpoSyncSchedulerTest {

    private final DartProperties enabledProps = new DartProperties(true, "test-key", 14);
    private final IpoFilterProperties filterProps = new IpoFilterProperties(new BigDecimal("500"), new BigDecimal("0.20"));
    private final DartClient dartClient = mock(DartClient.class);
    private final IpoDealRepository repository = mock(IpoDealRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    /** 보낸 알림 기록(V18 ipo_alert_log) — 스케줄러 인스턴스를 새로 만들어도(재기동) 남는 DB 대신. */
    private final List<IpoAlertLogEntity> alertRows = new ArrayList<>();
    private final IpoAlertLogRepository alertLog = alertLogBackedBy(alertRows);

    static IpoAlertLogRepository alertLogBackedBy(List<IpoAlertLogEntity> rows) {
        IpoAlertLogRepository log = mock(IpoAlertLogRepository.class);
        when(log.findByCorpCodeIn(any())).thenAnswer(inv -> List.copyOf(rows));
        when(log.save(any(IpoAlertLogEntity.class))).thenAnswer(inv -> {
            rows.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        return log;
    }

    private final IpoSyncScheduler scheduler =
            new IpoSyncScheduler(enabledProps, filterProps, dartClient, repository, alertLog, publisher, Clock.systemUTC());

    @Test
    void dart_enabled가_false면_아무것도_하지_않는다() {
        IpoSyncScheduler disabled = new IpoSyncScheduler(
                new DartProperties(false, "", 14), filterProps, dartClient, repository, alertLog, publisher, Clock.systemUTC());

        disabled.syncNow();

        verify(dartClient, never()).fetchRecentEquityFilings(any(), any());
        verify(repository, never()).findAll();
    }

    @Test
    void 두_지표_모두_임계치_충족시_RECOMMEND() {
        IpoDealEntity entity = new IpoDealEntity("01359815", "한울반도체", "20260910000583", "DART", Instant.now());
        entity.applyMetrics(new BigDecimal("600"), new BigDecimal("0.25"), Instant.now());

        scheduler.evaluateFilter(entity);

        assertEquals(IpoRecommendation.RECOMMEND, entity.getRecommendation());
    }

    @Test
    void 임계치_미충족이면_SKIP() {
        IpoDealEntity entity = new IpoDealEntity("01158632", "진코스텍", "20260910000579", "DART", Instant.now());
        entity.applyMetrics(new BigDecimal("300"), new BigDecimal("0.25"), Instant.now());

        scheduler.evaluateFilter(entity);

        assertEquals(IpoRecommendation.SKIP, entity.getRecommendation());
    }

    @Test
    void 지표가_하나라도_없으면_PENDING이고_사유에_어떤_지표인지_명시한다() {
        IpoDealEntity entity = new IpoDealEntity("01359815", "한울반도체", "20260910000583", "DART", Instant.now());

        scheduler.evaluateFilter(entity);

        assertEquals(IpoRecommendation.PENDING, entity.getRecommendation());
        assertTrue(entity.getRecommendReason().contains("기관경쟁률"));
        assertTrue(entity.getRecommendReason().contains("의무보유확약비율"));
    }

    @Test
    void 경계값_정확히_임계치와_같으면_RECOMMEND() {
        IpoDealEntity entity = new IpoDealEntity("01359815", "한울반도체", "20260910000583", "DART", Instant.now());
        entity.applyMetrics(new BigDecimal("500"), new BigDecimal("0.20"), Instant.now());

        scheduler.evaluateFilter(entity);

        assertEquals(IpoRecommendation.RECOMMEND, entity.getRecommendation());
    }

    @Test
    void 청약시작_D_1에_알림을_발행한다() {
        LocalDate today = LocalDate.of(2026, 9, 11);
        IpoDealEntity entity = new IpoDealEntity("01359815", "한울반도체", "20260910000583", "DART", Instant.now());
        entity.applyOfferingDetail(new DartClient.OfferingDetail(
                "20260910000583", today.plusDays(1), today.plusDays(2), null, "SK증권",
                new BigDecimal("5030"), 3_800_000L), Instant.now());
        when(repository.findAll()).thenReturn(List.of(entity));
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(DartClient.RecentFilings.complete(List.of()));

        schedulerAt(today).syncNow();

        var captor = org.mockito.ArgumentCaptor.forClass(IpoAlert.class);
        verify(publisher, times(1)).publishEvent(captor.capture());
        assertEquals("D-1", captor.getValue().phase());
        assertEquals("한울반도체", captor.getValue().corpName());
    }

    // ── 알림 중복 방지(2026-10-02, V16) — 회사 단위 1회, 재기동해도 같은 날 다시 보내지 않는다 ─────────────

    private static IpoDealEntity dealStarting(String corpCode, String corpName, String rceptNo, LocalDate start,
                                              String price) {
        IpoDealEntity entity = new IpoDealEntity(corpCode, corpName, rceptNo, "DART", Instant.now());
        entity.applyOfferingDetail(new DartClient.OfferingDetail(rceptNo, start, start.plusDays(1), null, "SK증권",
                new BigDecimal(price), 3_800_000L), Instant.now());
        return entity;
    }

    @Test
    void 같은_회사_신고서가_여러_건이면_청약_알림은_가장_최근_신고서로_한_번만_보낸다() {
        LocalDate today = LocalDate.of(2026, 10, 5);
        IpoDealEntity original = dealStarting("01158632", "진코스텍", "20260910000579", today.plusDays(1), "9000");
        IpoDealEntity amended = dealStarting("01158632", "진코스텍", "20261001000586", today.plusDays(1), "10000");
        when(repository.findAll()).thenReturn(List.of(original, amended));
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(DartClient.RecentFilings.complete(List.of()));

        schedulerAt(today).syncNow();

        var captor = org.mockito.ArgumentCaptor.forClass(IpoAlert.class);
        verify(publisher, times(1)).publishEvent(captor.capture());
        assertTrue(captor.getValue().message().contains("10,000원"), "최근(발행조건확정) 신고서의 공모가: " + captor.getValue().message());
        assertEquals(1, alertRows.size(), "회사 단위로 한 줄");
        assertEquals("01158632|D-1|" + today.plusDays(1), alertRows.get(0).key(), "기준값은 청약 시작일");
    }

    @Test
    void 재기동해도_같은_날_같은_단계_알림은_다시_보내지_않고_다음_단계는_보낸다() {
        LocalDate today = LocalDate.of(2026, 10, 5);
        IpoDealEntity deal = dealStarting("01359815", "한울반도체", "20260910000583", today.plusDays(1), "5030");
        when(repository.findAll()).thenReturn(List.of(deal));
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(DartClient.RecentFilings.complete(List.of()));

        schedulerAt(today).syncNow();
        schedulerAt(today).syncNow();     // 새 인스턴스 = 재기동(메모리 기록 없음) — DB 표시로 막는다
        verify(publisher, times(1)).publishEvent(any(IpoAlert.class));

        schedulerAt(today.plusDays(1)).syncNow();   // 청약 당일 — START는 따로 보낸다
        var captor = org.mockito.ArgumentCaptor.forClass(IpoAlert.class);
        verify(publisher, times(2)).publishEvent(captor.capture());
        assertEquals("START", captor.getAllValues().get(1).phase());
    }

    @Test
    void 청약기간_중이_아니고_D_1_당일도_아니면_알림을_발행하지_않는다() {
        LocalDate today = LocalDate.of(2026, 9, 11);
        IpoDealEntity entity = new IpoDealEntity("01359815", "한울반도체", "20260910000583", "DART", Instant.now());
        entity.applyOfferingDetail(new DartClient.OfferingDetail(
                "20260910000583", today.plusDays(10), today.plusDays(11), null, "SK증권",
                new BigDecimal("5030"), 3_800_000L), Instant.now());
        when(repository.findAll()).thenReturn(List.of(entity));
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(DartClient.RecentFilings.complete(List.of()));

        schedulerAt(today).syncNow();

        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void 상태_재계산_청약기간_중이면_SUBSCRIBING() {
        IpoDealEntity entity = new IpoDealEntity("01359815", "한울반도체", "20260910000583", "DART", Instant.now());
        LocalDate today = LocalDate.of(2026, 11, 9);
        entity.applyOfferingDetail(new DartClient.OfferingDetail(
                "20260910000583", LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 10), null, "SK증권",
                new BigDecimal("5030"), 3_800_000L), Instant.now());

        scheduler.recalculateStatus(entity, today);

        assertEquals(IpoStatus.SUBSCRIBING, entity.getStatus());
    }

    @Test
    void 상태_재계산_청약종료_이후면_PASSED() {
        IpoDealEntity entity = new IpoDealEntity("01359815", "한울반도체", "20260910000583", "DART", Instant.now());
        entity.applyOfferingDetail(new DartClient.OfferingDetail(
                "20260910000583", LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 10), null, "SK증권",
                new BigDecimal("5030"), 3_800_000L), Instant.now());

        scheduler.recalculateStatus(entity, LocalDate.of(2026, 11, 20));

        assertEquals(IpoStatus.PASSED, entity.getStatus());
    }

    @Test
    void 신규딜은_rcept_no_기준으로_upsert된다() {
        LocalDate since = LocalDate.of(2026, 8, 28);
        LocalDate today = LocalDate.of(2026, 9, 11);
        var notice = new DartClient.DealNotice("20260910000579", "01158632", "진코스텍",
                "[기재정정]증권신고서(지분증권)", LocalDate.of(2026, 9, 10));
        when(dartClient.fetchRecentEquityFilings(since, today)).thenReturn(DartClient.RecentFilings.complete(List.of(notice)));
        when(repository.findByRceptNo("20260910000579")).thenReturn(Optional.empty());
        when(dartClient.fetchOffering(eq("01158632"), eq("20260910000579"), any(), any()))
                .thenReturn(Optional.of(new DartClient.OfferingLookup(new DartClient.OfferingDetail(
                        "20260910000579", LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 6),
                        LocalDate.of(2026, 10, 8), "하나증권", new BigDecimal("19500"), 852_000L),
                        OfferingKind.IPO, "일반공모")));
        when(repository.findAll()).thenReturn(List.of());

        schedulerAt(today).syncNow();

        var captor = org.mockito.ArgumentCaptor.forClass(IpoDealEntity.class);
        verify(repository, times(1)).save(captor.capture());
        IpoDealEntity saved = captor.getValue();
        assertEquals("진코스텍", saved.getCorpName());
        assertEquals("하나증권", saved.getLeadManager());
        assertFalse(saved.getSubscriptionStart() == null);
        assertEquals(OfferingKind.IPO, saved.getOfferingKind());
    }

    // ── 2026-10-02: 상장사 유상증자 제외·수요예측 지표 자동 입력(aiDoc/ipo-demand-forecast.md) ─────────────

    @Test
    void 상장사_유상증자는_새_딜로_만들지_않는다() {
        LocalDate today = LocalDate.of(2026, 9, 11);
        var notice = new DartClient.DealNotice("20260910000583", "01359815", "한울반도체",
                "[기재정정]증권신고서(지분증권)", LocalDate.of(2026, 9, 10));
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(DartClient.RecentFilings.complete(List.of(notice)));
        when(repository.findByRceptNo("20260910000583")).thenReturn(Optional.empty());
        when(dartClient.fetchOffering(eq("01359815"), eq("20260910000583"), any(), any()))
                .thenReturn(Optional.of(new DartClient.OfferingLookup(null, OfferingKind.RIGHTS, "주주배정후 실권주 일반공모")));
        when(repository.findAll()).thenReturn(List.of());

        IpoSyncScheduler.SyncSummary summary = schedulerAt(today).sync();

        verify(repository, never()).save(any());
        assertEquals(1, summary.excluded());
        assertEquals(0, summary.created());
    }

    @Test
    void 주요정보가_없으면_공시_본문으로_판정한다() {
        LocalDate today = LocalDate.of(2026, 9, 15);
        var notice = new DartClient.DealNotice("20260914000188", "00547510", "툴젠",
                "[발행조건확정]증권신고서(지분증권)", LocalDate.of(2026, 9, 14));
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(DartClient.RecentFilings.complete(List.of(notice)));
        when(repository.findByRceptNo("20260914000188")).thenReturn(Optional.empty());
        when(dartClient.fetchDocument("20260914000188"))
                .thenReturn(Optional.of(DartFixtures.xml("20260914000188.zip"))); // 신주배정기준일·구주주 → 유상증자
        when(repository.findAll()).thenReturn(List.of());

        IpoSyncScheduler.SyncSummary summary = schedulerAt(today).sync();

        verify(repository, never()).save(any());
        assertEquals(1, summary.excluded());
    }

    @Test
    void 유상증자로_판정된_기존_딜은_권고와_청약_알림에서_뺀다() {
        LocalDate today = LocalDate.of(2026, 11, 8);
        IpoDealEntity rights = new IpoDealEntity("01359815", "한울반도체", "20260910000583", "DART", Instant.now());
        rights.applyOfferingDetail(new DartClient.OfferingDetail(
                "20260910000583", today.plusDays(1), today.plusDays(2), null, "SK증권",
                new BigDecimal("5030"), 3_800_000L), Instant.now());
        rights.classifyOffering(OfferingKind.RIGHTS, Instant.now());
        String reasonBefore = rights.getRecommendReason();
        when(repository.findAll()).thenReturn(List.of(rights));
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(DartClient.RecentFilings.complete(List.of()));

        schedulerAt(today).syncNow();

        verify(publisher, never()).publishEvent(any()); // 청약 D-1이어도 알리지 않는다
        assertEquals(reasonBefore, rights.getRecommendReason());
        verify(dartClient, never()).fetchCorpEquityFilings(any(), any(), any()); // 지표도 찾지 않는다
    }

    @Test
    void 옛_딜의_공모_종류를_판정해_같은_회사_딜에_남긴다() {
        LocalDate today = LocalDate.of(2026, 10, 2);
        IpoDealEntity older = new IpoDealEntity("01344202", "뷰노", "20260911000606", "DART", Instant.now());
        IpoDealEntity newer = new IpoDealEntity("01344202", "뷰노", "20260929000672", "DART", Instant.now());
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(DartClient.RecentFilings.complete(List.of()));
        when(repository.findByOfferingKindIsNull()).thenReturn(List.of(older, newer));
        when(repository.findByCorpCode("01344202")).thenReturn(List.of(older, newer));
        when(dartClient.fetchOffering(eq("01344202"), eq("20260929000672"), any(), any()))
                .thenReturn(Optional.of(new DartClient.OfferingLookup(null, OfferingKind.RIGHTS, "주주배정후 실권주 일반공모")));
        when(repository.findAll()).thenReturn(List.of(older, newer));

        IpoSyncScheduler.SyncSummary summary = schedulerAt(today).sync();

        assertEquals(OfferingKind.RIGHTS, older.getOfferingKind());
        assertEquals(OfferingKind.RIGHTS, newer.getOfferingKind());
        assertEquals(2, summary.classified());
    }

    @Test
    void 발행조건확정_수요예측_결과로_지표를_자동_입력하고_청약_전날_알림에_권고를_싣는다() {
        LocalDate today = LocalDate.of(2026, 9, 17);
        IpoDealEntity brils = new IpoDealEntity("01801026", "브릴스", "20260825000476", "DART", Instant.now());
        brils.applyOfferingDetail(new DartClient.OfferingDetail(
                "20260825000476", today.plusDays(1), today.plusDays(2), null, "한국투자증권",
                new BigDecimal("19500"), null), Instant.now());
        brils.classifyOffering(OfferingKind.IPO, Instant.now());
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(DartClient.RecentFilings.complete(List.of()));
        when(repository.findAll()).thenReturn(List.of(brils));
        when(repository.findByCorpCode("01801026")).thenReturn(List.of(brils));
        when(dartClient.fetchCorpEquityFilings(eq("01801026"), any(), any())).thenReturn(List.of(
                new DartClient.DealNotice("20260916000234", "01801026", "브릴스", "[발행조건확정]증권신고서(지분증권)",
                        LocalDate.of(2026, 9, 16)),
                new DartClient.DealNotice("20260825000476", "01801026", "브릴스", "증권신고서(지분증권)",
                        LocalDate.of(2026, 8, 25))));
        when(dartClient.fetchDocument("20260916000234"))
                .thenReturn(Optional.of(DartFixtures.xml("20260916000234-demand-forecast.xml")));

        IpoSyncScheduler.SyncSummary summary = schedulerAt(today).sync();

        assertEquals(new BigDecimal("1187.74"), brils.getInstitutionalCompetitionRate());
        assertEquals(new BigDecimal("0.2175"), brils.getLockupCommitRate());
        assertEquals(IpoDealEntity.MetricsSource.DART, brils.getMetricsSource());
        assertEquals("20260916000234", brils.getMetricsRceptNo());
        assertEquals(IpoRecommendation.RECOMMEND, brils.getRecommendation()); // 500:1·20% 둘 다 넘는다
        assertTrue(brils.getRecommendReason().contains("21.75%"), brils.getRecommendReason());
        assertTrue(brils.getRecommendReason().contains("DART 수요예측 결과 20260916000234"), brils.getRecommendReason());
        assertEquals(1, summary.metricsFilled());
        var captor = org.mockito.ArgumentCaptor.forClass(IpoAlert.class);
        verify(publisher, times(2)).publishEvent(captor.capture());   // 수요예측 결과 + 청약 D-1
        IpoAlert metrics = captor.getAllValues().stream().filter(a -> a.phase().equals("METRICS")).findFirst().orElseThrow();
        assertTrue(metrics.message().contains("기관경쟁률 1,187.74:1 · 의무보유확약 21.75% → 권고 RECOMMEND(청약 권고)"),
                metrics.message());
        IpoAlert dMinus1 = captor.getAllValues().stream().filter(a -> a.phase().equals("D-1")).findFirst().orElseThrow();
        assertTrue(dMinus1.message().contains("RECOMMEND"), dMinus1.message());
        verify(dartClient, never()).fetchDocument("20260825000476"); // 확정 신고서만 읽는다
    }

    @Test
    void 사람이_넣은_지표는_자동_입력이_덮지_않는다() {
        LocalDate today = LocalDate.of(2026, 9, 17);
        IpoDealEntity deal = new IpoDealEntity("01801026", "브릴스", "20260825000476", "DART", Instant.now());
        deal.applyMetrics(new BigDecimal("900"), new BigDecimal("0.30"), Instant.now());
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(DartClient.RecentFilings.complete(List.of()));
        when(repository.findAll()).thenReturn(List.of(deal));
        when(repository.findByCorpCode("01801026")).thenReturn(List.of(deal));
        // 확정 공모가를 찾으러 [발행조건확정] 원본은 읽는다(2026-10-13) — 그래도 수요예측 지표는 사람이 넣은 값 그대로다
        when(dartClient.fetchCorpEquityFilings(eq("01801026"), any(), any())).thenReturn(List.of(
                new DartClient.DealNotice("20260916000234", "01801026", "브릴스", "[발행조건확정]증권신고서(지분증권)",
                        LocalDate.of(2026, 9, 16))));
        when(dartClient.fetchDocument("20260916000234"))
                .thenReturn(Optional.of(DartFixtures.xml("20260916000234-demand-forecast.xml")));

        schedulerAt(today).sync();

        assertEquals(IpoDealEntity.MetricsSource.MANUAL, deal.getMetricsSource());
        assertEquals(new BigDecimal("900"), deal.getInstitutionalCompetitionRate());
        assertEquals(new BigDecimal("0.30"), deal.getLockupCommitRate());
        assertFalse(deal.applyDemandForecast(new BigDecimal("1"), new BigDecimal("0.01"), "20260916000234", Instant.now()));
        assertEquals(new BigDecimal("0.30"), deal.getLockupCommitRate());
    }

    /** 주어진 KST 날짜의 정오를 "오늘"로 보는 스케줄러 — Clock 주입으로 결정론적 테스트. */
    private IpoSyncScheduler schedulerAt(LocalDate today) {
        Clock fixed = Clock.fixed(today.atTime(12, 0).toInstant(ZoneOffset.of("+09:00")), ZoneOffset.UTC);
        return new IpoSyncScheduler(enabledProps, filterProps, dartClient, repository, alertLog, publisher, fixed);
    }

    // ── 수집 요약(2026-10-01 로그 점검 F-4, aiDoc/run-summary-logs.md) ──────────────────────

    @Test
    void 수집이_끝나면_신규_실패_건수와_전체_딜_수를_요약한다() {
        LocalDate today = LocalDate.of(2026, 10, 1);
        DartClient.DealNotice fresh = new DartClient.DealNotice("20260930000001", "00000001", "새회사", "증권신고서(지분증권)", today.minusDays(1));
        DartClient.DealNotice known = new DartClient.DealNotice("20260925000002", "00000002", "기존회사", "증권신고서(지분증권)", today.minusDays(6));
        DartClient.DealNotice broken = new DartClient.DealNotice("20260929000003", "00000003", "실패회사", "증권신고서(지분증권)", today.minusDays(2));
        IpoDealEntity knownEntity = new IpoDealEntity("00000002", "기존회사", "20260925000002", "DART", Instant.now());
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(DartClient.RecentFilings.complete(List.of(fresh, known, broken)));
        when(repository.findByRceptNo("20260930000001")).thenReturn(Optional.empty());
        when(repository.findByRceptNo("20260925000002")).thenReturn(Optional.of(knownEntity));
        when(repository.findByRceptNo("20260929000003")).thenThrow(new RuntimeException("DB 오류(테스트)"));
        when(dartClient.fetchOffering(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(repository.findAll()).thenReturn(List.of(knownEntity));

        IpoSyncScheduler.SyncSummary summary = schedulerAt(today).sync();

        assertEquals(3, summary.filings());
        assertEquals(1, summary.created());
        assertEquals(1, summary.failed());
        assertEquals(0, summary.skipped());
        assertEquals(1, summary.totalDeals());
        assertEquals("공모주 수집 완료 — 2026-09-17~2026-10-01 증권신고(지분증권) 3건(목록 1/1쪽, 신규 딜 1·유상증자 제외 0·저장 생략 0·실패 1), "
                        + "옛 딜 공모 종류 판정 0건, 자동 입력 수요예측 지표 0·확정 공모가 0개 회사, 전체 딜 1건 상태 재계산, 알림 0건",
                summary.toLogLine());
    }

    @Test
    void 비활성이면_요약이_없다() {
        IpoSyncScheduler disabled = new IpoSyncScheduler(
                new DartProperties(false, "test-key", 14), filterProps, dartClient, repository, alertLog, publisher, Clock.systemUTC());

        assertEquals(null, disabled.sync());
    }

    // ── 2026-10-13: 목록 전 쪽·실패 재시도(L-5), 밤 알림 금지, 단계 알림, 상장 확인(aiDoc/ipo-automation.md) ─────────

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 시각을 옮길 수 있는 시계(KST 시각으로 맞춘다). */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(LocalDateTime kst) {
            set(kst);
        }

        void set(LocalDateTime kst) {
            now = kst.atZone(KST).toInstant();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static DartClient.OfferingDetail jincostechDetail() {
        return new DartClient.OfferingDetail("20260910000579", LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 6),
                LocalDate.of(2026, 10, 8), "하나증권", new BigDecimal("19500"), 852_000L);
    }

    private static IpoDealEntity jincostech() {
        IpoDealEntity deal = new IpoDealEntity("01158632", "진코스텍", "20260910000579", "DART",
                Instant.parse("2026-09-10T03:00:00Z"));
        deal.applyOfferingDetail(jincostechDetail(), Instant.now());
        deal.classifyOffering(OfferingKind.IPO, Instant.now());
        return deal;
    }

    @Test
    void 공시_목록을_끝까지_읽지_못하면_오늘_수집으로_기록하지_않고_매시_점검이_다시_수집한다() {
        MutableClock clock = new MutableClock(LocalDateTime.of(2026, 10, 13, 8, 20));
        IpoSyncScheduler scheduler = new IpoSyncScheduler(enabledProps, filterProps, dartClient, repository, alertLog,
                publisher, clock);
        when(repository.findAll()).thenReturn(List.of());
        when(dartClient.fetchRecentEquityFilings(any(), any()))
                .thenReturn(new DartClient.RecentFilings(List.of(), false, 3, 11))   // 4쪽째에서 실패(10/13 전에는 "완료"로 남았다)
                .thenReturn(new DartClient.RecentFilings(List.of(), true, 11, 11));

        IpoSyncScheduler.SyncSummary first = scheduler.sync();
        clock.set(LocalDateTime.of(2026, 10, 13, 9, 5));
        scheduler.catchUp("시간별 점검");   // 성공 기록이 없어 다시 수집 — 이번엔 끝까지
        clock.set(LocalDateTime.of(2026, 10, 13, 10, 5));
        scheduler.catchUp("시간별 점검");   // 오늘은 성공했으니 하지 않는다

        assertFalse(first.complete());
        assertTrue(first.toLogLine().startsWith("공모주 수집 미완료(공시 목록을 끝까지 읽지 못함"), first.toLogLine());
        assertTrue(first.toLogLine().contains("목록 3/11쪽"), first.toLogLine());
        verify(dartClient, times(2)).fetchRecentEquityFilings(any(), any());
    }

    @Test
    void 밤에는_알림을_보내지_않고_07시_이후_매시_점검에서_한_번_보낸다() {
        MutableClock clock = new MutableClock(LocalDateTime.of(2026, 10, 18, 0, 5));   // 일요일 00:05 매시 점검
        IpoDealEntity deal = dealStarting("01359815", "한울반도체", "20260910000583", LocalDate.of(2026, 10, 19), "5030");
        when(repository.findAll()).thenReturn(List.of(deal));
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(DartClient.RecentFilings.complete(List.of()));
        IpoSyncScheduler scheduler = new IpoSyncScheduler(enabledProps, filterProps, dartClient, repository, alertLog,
                publisher, clock);

        scheduler.catchUp("시간별 점검");   // 00:05 — 수집은 하고 청약 D-1 알림은 미룬다
        verify(publisher, never()).publishEvent(any());
        clock.set(LocalDateTime.of(2026, 10, 18, 7, 5));
        scheduler.catchUp("시간별 점검");   // 07:05 — 수집 없이 미룬 알림만 보낸다
        clock.set(LocalDateTime.of(2026, 10, 18, 8, 5));
        scheduler.catchUp("시간별 점검");

        var captor = org.mockito.ArgumentCaptor.forClass(IpoAlert.class);
        verify(publisher, times(1)).publishEvent(captor.capture());
        assertEquals("D-1", captor.getValue().phase());
        assertTrue(captor.getValue().message().contains("내일 10/19(월)부터 청약"), captor.getValue().message());
        verify(dartClient, times(1)).fetchRecentEquityFilings(any(), any());
    }

    @Test
    void 발행조건확정_원본에서_확정_공모가를_읽어_수요예측_결과_알림에_한_번_싣는다() {
        LocalDate today = LocalDate.of(2026, 9, 29);
        IpoDealEntity deal = jincostech();
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(DartClient.RecentFilings.complete(List.of()));
        when(repository.findAll()).thenReturn(List.of(deal));
        when(repository.findByCorpCode("01158632")).thenReturn(List.of(deal));
        when(dartClient.fetchCorpEquityFilings(eq("01158632"), any(), any())).thenReturn(List.of(
                new DartClient.DealNotice("20260928000402", "01158632", "진코스텍", "[발행조건확정]증권신고서(지분증권)",
                        LocalDate.of(2026, 9, 28))));
        when(dartClient.fetchDocument("20260928000402")).thenReturn(Optional.of(DartFixtures.xml("20260928000402.zip")));

        IpoSyncScheduler.SyncSummary summary = schedulerAt(today).sync();
        schedulerAt(today).sync();   // 다시 돌아도(재기동) 같은 알림은 없다

        assertEquals(new BigDecimal("23500"), deal.getOfferPriceFinal());
        assertEquals(new BigDecimal("19500"), deal.getOfferPriceConfirmed(), "주요정보 모집가액(희망가 하단)은 그대로 둔다");
        assertEquals(1, summary.pricesFilled());
        assertEquals(1, summary.metricsFilled());
        assertEquals(IpoRecommendation.SKIP, deal.getRecommendation());   // 경쟁률 1,097:1은 넘지만 확약 5.29% < 20%
        var captor = org.mockito.ArgumentCaptor.forClass(IpoAlert.class);
        verify(publisher, times(1)).publishEvent(captor.capture());
        assertEquals("METRICS", captor.getValue().phase());
        assertEquals("[수요예측 결과] 진코스텍 — 확정 공모가 23,500원 · 기관경쟁률 1,097.62:1 · 의무보유확약 5.29% → "
                + "권고 SKIP(청약 비권고). 청약 10/2(금)~10/6(화), 하나증권.", captor.getValue().message());
    }

    @Test
    void 새로_잡힌_공모주는_한_통으로_모아_알리고_유상증자와_판정_전_딜은_뺀다() {
        LocalDate today = LocalDate.of(2026, 10, 14);
        Instant created = today.atTime(8, 20).atZone(KST).toInstant();
        IpoDealEntity melcon = new IpoDealEntity("01111111", "멜콘", "20261013000001", "DART", created);
        melcon.applyOfferingDetail(new DartClient.OfferingDetail("20261013000001", LocalDate.of(2026, 10, 27),
                LocalDate.of(2026, 10, 28), LocalDate.of(2026, 10, 30), "대신증권", new BigDecimal("10700"), 2_500_000L), created);
        melcon.classifyOffering(OfferingKind.IPO, created);
        IpoDealEntity spac = new IpoDealEntity("02222222", "한화플러스제6호기업인수목적", "20261013000002", "DART", created);
        spac.classifyOffering(OfferingKind.IPO, created);
        IpoDealEntity rights = new IpoDealEntity("03333333", "상장사", "20261013000003", "DART", created);
        rights.classifyOffering(OfferingKind.RIGHTS, created);
        IpoDealEntity unknown = new IpoDealEntity("04444444", "판정전", "20261013000004", "DART", created);
        when(repository.findAll()).thenReturn(List.of(melcon, spac, rights, unknown));
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(DartClient.RecentFilings.complete(List.of()));

        schedulerAt(today).sync();
        schedulerAt(today).sync();

        var captor = org.mockito.ArgumentCaptor.forClass(IpoAlert.class);
        verify(publisher, times(1)).publishEvent(captor.capture());
        assertEquals("NEW", captor.getValue().phase());
        assertEquals("[공모주 신규] 2건 — 멜콘(청약 10/27(화)~10/28(수), 대신증권, 희망가 하단 10,700원) · "
                + "한화플러스제6호기업인수목적(청약 일정 미정). 수요예측 결과가 나오면 권고와 함께 다시 알린다.", captor.getValue().message());
        assertEquals(2, alertRows.size());
    }

    @Test
    void 상장_확인_이벤트로_상장일과_종목코드를_채우고_관심_딜에_상장일_알림을_보낸다() {
        LocalDate today = LocalDate.of(2026, 10, 15);
        IpoDealEntity deal = jincostech();
        deal.applyFinalPrice(new BigDecimal("23500"), null, null, Instant.now());
        deal.applyRecord(20, null, 10, null, null, null, Instant.now());   // 청약 20주, 배정 10주 — 관심 딜
        when(repository.findAll()).thenReturn(List.of(deal));

        schedulerAt(today).onRecentListings(new com.autostock.common.event.RecentListings(today, List.of(
                new com.autostock.common.event.RecentListings.Listing("250030", "진코스텍", today, "코스닥"),
                new com.autostock.common.event.RecentListings.Listing("123450", "다른회사", today, "코스닥"))));

        assertEquals(today, deal.getListingDate());
        assertEquals("250030", deal.getStockCode());
        assertEquals(IpoStatus.LISTED, deal.getStatus());
        var captor = org.mockito.ArgumentCaptor.forClass(IpoAlert.class);
        verify(publisher, times(1)).publishEvent(captor.capture());
        assertEquals("[상장일] 진코스텍(250030) — 오늘 상장. 공모가 23,500원(확정), 배정 10주(기록 기준). 매도는 영웅문S#에서 수동.",
                captor.getValue().message());
    }

    @Test
    void 관심_딜이_아니면_청약_마감일_알림이_없고_청약을_기록하면_보낸다() {
        LocalDate today = LocalDate.of(2026, 10, 6);   // 청약 마감일
        IpoDealEntity deal = jincostech();
        when(repository.findAll()).thenReturn(List.of(deal));
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(DartClient.RecentFilings.complete(List.of()));

        schedulerAt(today).sync();
        verify(publisher, never()).publishEvent(any());   // 권고 PENDING·기록 없음

        deal.applyRecord(20, null, null, null, null, null, Instant.now());
        schedulerAt(today).sync();

        var captor = org.mockito.ArgumentCaptor.forClass(IpoAlert.class);
        verify(publisher, times(1)).publishEvent(captor.capture());
        assertEquals("END", captor.getValue().phase());
    }
}

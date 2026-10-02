package com.autostock.ipo;

import java.time.Instant;
import com.autostock.common.event.IpoAlert;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

    private final IpoSyncScheduler scheduler =
            new IpoSyncScheduler(enabledProps, filterProps, dartClient, repository, publisher, Clock.systemUTC());

    @Test
    void dart_enabled가_false면_아무것도_하지_않는다() {
        IpoSyncScheduler disabled = new IpoSyncScheduler(
                new DartProperties(false, "", 14), filterProps, dartClient, repository, publisher, Clock.systemUTC());

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
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(List.of());

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
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(List.of());

        schedulerAt(today).syncNow();

        var captor = org.mockito.ArgumentCaptor.forClass(IpoAlert.class);
        verify(publisher, times(1)).publishEvent(captor.capture());
        assertTrue(captor.getValue().message().contains("10000원"), "최근(발행조건확정) 신고서의 공모가: " + captor.getValue().message());
        assertTrue(original.alreadyAlerted("D-1", today) && amended.alreadyAlerted("D-1", today), "회사의 모든 딜에 표시");
    }

    @Test
    void 재기동해도_같은_날_같은_단계_알림은_다시_보내지_않고_다음_단계는_보낸다() {
        LocalDate today = LocalDate.of(2026, 10, 5);
        IpoDealEntity deal = dealStarting("01359815", "한울반도체", "20260910000583", today.plusDays(1), "5030");
        when(repository.findAll()).thenReturn(List.of(deal));
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(List.of());

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
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(List.of());

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
        when(dartClient.fetchRecentEquityFilings(since, today)).thenReturn(List.of(notice));
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
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(List.of(notice));
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
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(List.of(notice));
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
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(List.of());

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
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(List.of());
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
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(List.of());
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
        verify(publisher).publishEvent(captor.capture());
        assertTrue(captor.getValue().message().contains("RECOMMEND"), captor.getValue().message());
        verify(dartClient, never()).fetchDocument("20260825000476"); // 확정 신고서만 읽는다
    }

    @Test
    void 사람이_넣은_지표는_자동_입력이_덮지_않는다() {
        LocalDate today = LocalDate.of(2026, 9, 17);
        IpoDealEntity deal = new IpoDealEntity("01801026", "브릴스", "20260825000476", "DART", Instant.now());
        deal.applyMetrics(new BigDecimal("900"), new BigDecimal("0.30"), Instant.now());
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(List.of());
        when(repository.findAll()).thenReturn(List.of(deal));

        schedulerAt(today).sync();

        assertEquals(IpoDealEntity.MetricsSource.MANUAL, deal.getMetricsSource());
        assertEquals(new BigDecimal("900"), deal.getInstitutionalCompetitionRate());
        verify(dartClient, never()).fetchCorpEquityFilings(any(), any(), any());
        assertFalse(deal.applyDemandForecast(new BigDecimal("1"), new BigDecimal("0.01"), "20260916000234", Instant.now()));
        assertEquals(new BigDecimal("0.30"), deal.getLockupCommitRate());
    }

    /** 주어진 KST 날짜의 정오를 "오늘"로 보는 스케줄러 — Clock 주입으로 결정론적 테스트. */
    private IpoSyncScheduler schedulerAt(LocalDate today) {
        Clock fixed = Clock.fixed(today.atTime(12, 0).toInstant(ZoneOffset.of("+09:00")), ZoneOffset.UTC);
        return new IpoSyncScheduler(enabledProps, filterProps, dartClient, repository, publisher, fixed);
    }

    // ── 수집 요약(2026-10-01 로그 점검 F-4, aiDoc/run-summary-logs.md) ──────────────────────

    @Test
    void 수집이_끝나면_신규_실패_건수와_전체_딜_수를_요약한다() {
        LocalDate today = LocalDate.of(2026, 10, 1);
        DartClient.DealNotice fresh = new DartClient.DealNotice("20260930000001", "00000001", "새회사", "증권신고서(지분증권)", today.minusDays(1));
        DartClient.DealNotice known = new DartClient.DealNotice("20260925000002", "00000002", "기존회사", "증권신고서(지분증권)", today.minusDays(6));
        DartClient.DealNotice broken = new DartClient.DealNotice("20260929000003", "00000003", "실패회사", "증권신고서(지분증권)", today.minusDays(2));
        IpoDealEntity knownEntity = new IpoDealEntity("00000002", "기존회사", "20260925000002", "DART", Instant.now());
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(List.of(fresh, known, broken));
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
        assertEquals("공모주 수집 완료 — 2026-09-17~2026-10-01 증권신고(지분증권) 3건(신규 딜 1·유상증자 제외 0·저장 생략 0·실패 1), "
                        + "옛 딜 공모 종류 판정 0건, 수요예측 지표 자동 입력 0개 회사, 전체 딜 1건 상태 재계산",
                summary.toLogLine());
    }

    @Test
    void 비활성이면_요약이_없다() {
        IpoSyncScheduler disabled = new IpoSyncScheduler(
                new DartProperties(false, "test-key", 14), filterProps, dartClient, repository, publisher, Clock.systemUTC());

        assertEquals(null, disabled.sync());
    }
}

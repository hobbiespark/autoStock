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
    void 기관경쟁률이_100대_1_미만이면_패스() {
        IpoDealEntity entity = new IpoDealEntity("01158632", "진코스텍", "20260910000579", "DART", Instant.now());
        entity.applyMetrics(new BigDecimal("63"), new BigDecimal("0.25"), Instant.now());

        scheduler.evaluateFilter(entity);

        assertEquals(IpoRecommendation.SKIP, entity.getRecommendation());
        assertEquals("PASS", entity.getAdviceGrade());
        assertTrue(entity.getRecommendReason().startsWith("패스(점수 -2)"), entity.getRecommendReason());
    }

    @Test
    void 스팩은_기관경쟁률이_높아도_추천하지_않는다() {
        // 2026-10-16 로그: 에스케이증권제14호기업인수목적(기관 1,228.38:1, 공모가 2,000원)이 "균등만(+2)"을 받았다
        IpoDealEntity entity = new IpoDealEntity("01234567", "에스케이증권제14호기업인수목적", "20261014000196", "DART",
                Instant.now());
        entity.applyMetrics(new BigDecimal("1228.38"), null, Instant.now());

        scheduler.evaluateFilter(entity);

        assertEquals(IpoRecommendation.SKIP, entity.getRecommendation());
        assertEquals("PASS", entity.getAdviceGrade());
        assertTrue(entity.getRecommendReason().startsWith("패스 — 스팩"), entity.getRecommendReason());
        assertTrue(IpoSyncScheduler.isSpac("엔에이치스팩33호"));
        assertFalse(IpoSyncScheduler.isSpac("진코스텍"));
    }

    @Test
    void 스팩은_유통가능물량을_찾지_않는다() {
        // 2026-10-16 로그: 아이엠기업인수목적1호·에스케이증권제14호기업인수목적 "유통가능물량 문장을 찾지 못함 (내일 다시)" 매일 반복
        IpoDealEntity spac = new IpoDealEntity("01234567", "에스케이증권제14호기업인수목적", "20261014000196", "DART",
                Instant.now());
        spac.classifyOffering(OfferingKind.IPO, Instant.now());
        IpoDealEntity normal = new IpoDealEntity("01234568", "티앤이코리아", "20260923000255", "DART", Instant.now());
        normal.classifyOffering(OfferingKind.IPO, Instant.now());

        assertFalse(IpoDemandForecastCollector.wantsFloat(spac));
        assertTrue(IpoDemandForecastCollector.wantsFloat(normal));
        // 2026-10-22: 상장했거나 청약이 끝났으면 그만 찾는다(멜콘·진코스텍이 상장 뒤에도 매일 "찾지 못함")
        assertTrue(IpoDemandForecastCollector.wantsFloat(normal, LocalDate.of(2026, 10, 1)));
        normal.applyMetrics(null, null, LocalDate.of(2026, 10, 15), Instant.now());   // 상장일
        assertFalse(IpoDemandForecastCollector.wantsFloat(normal, LocalDate.of(2026, 10, 15)));
    }

    @Test
    void 예전_임계_300대_1은_이제_균등만이다() {
        // 2026-10-15 청약 추천으로 바뀜 — 300~500:1은 0점. 다른 감점이 없으면 균등 최소 청약을 권한다
        IpoDealEntity entity = new IpoDealEntity("01158632", "진코스텍", "20260910000579", "DART", Instant.now());
        entity.applyMetrics(new BigDecimal("300"), new BigDecimal("0.25"), Instant.now());

        scheduler.evaluateFilter(entity);

        assertEquals(IpoRecommendation.RECOMMEND, entity.getRecommendation());
        assertEquals("EQUAL_ONLY", entity.getAdviceGrade());
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
        entity.applyMetrics(new BigDecimal("1200"), new BigDecimal("0.30"), Instant.now());   // 청약 권고 딜만 D-1(2026-10-22)
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
        // 청약 D-1·시작 알림은 청약 권고 딜만(2026-10-22) — 기관 1,200:1(+2)이면 균등만
        entity.applyMetrics(new BigDecimal("1200"), new BigDecimal("0.30"), Instant.now());
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
        assertEquals(IpoRecommendation.RECOMMEND, brils.getRecommendation()); // 청약 추천 균등만(기관 1,000:1 이상 +2)
        assertEquals("EQUAL_ONLY", brils.getAdviceGrade());
        assertEquals(2, brils.getAdviceScore());
        assertTrue(brils.getRecommendReason().contains("21.75%"), brils.getRecommendReason());
        assertTrue(brils.getRecommendReason().contains("DART 수요예측 결과 20260916000234"), brils.getRecommendReason());
        assertEquals(1, summary.metricsFilled());
        var captor = org.mockito.ArgumentCaptor.forClass(IpoAlert.class);
        verify(publisher, times(2)).publishEvent(captor.capture());   // 수요예측 결과 + 청약 D-1
        IpoAlert metrics = captor.getAllValues().stream().filter(a -> a.phase().equals("METRICS")).findFirst().orElseThrow();
        assertTrue(metrics.message().startsWith("[청약 추천] 브릴스 — 확정 공모가 미확인. 균등만(점수 +2) — 기관 1,187.74:1(+2) · 확약 21.75%"),
                metrics.message());
        assertTrue(metrics.message().contains("청약: 균등 최소 단위(보통 10주)만. 증권사 한 곳만. 상장일 시초가 매도."), metrics.message());
        assertTrue(!metrics.message().contains("DART 수요예측 결과"), "알림에는 출처 꼬리표를 싣지 않는다: " + metrics.message());
        IpoAlert dMinus1 = captor.getAllValues().stream().filter(a -> a.phase().equals("D-1")).findFirst().orElseThrow();
        assertTrue(dMinus1.message().contains("추천: 균등만(점수 +2)"), dMinus1.message());
        // 수요예측 결과는 확정 신고서에서, 유통가능물량은 원 증권신고서에서 읽는다(2026-10-15)
        verify(dartClient, times(1)).fetchDocument("20260825000476");
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
                        + "옛 딜 공모 종류 판정 0건, 자동 입력 수요예측 지표 0·확정 공모가 0·유통가능물량 0개 회사, 전체 딜 1건 상태 재계산, 알림 0건",
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
        // 예전 규칙(확약 20%)은 SKIP이었다. 청약 추천(2026-10-15)은 기관 1,000:1 이상 +2 — 맥락(직전 딜)이 없으면 확약은 0점
        assertEquals(IpoRecommendation.RECOMMEND, deal.getRecommendation());
        var captor = org.mockito.ArgumentCaptor.forClass(IpoAlert.class);
        verify(publisher, times(1)).publishEvent(captor.capture());
        assertEquals("METRICS", captor.getValue().phase());
        assertEquals("[청약 추천] 진코스텍 — 확정 공모가 23,500원. 균등만(점수 +2) — 기관 1,097.62:1(+2) · "
                + "확약 5.29%(직전 비교 표본 0건 — 점수 없음) · 밴드 위치 미확인 · 유통가능물량 미확인 · 분위기 판단 표본 부족(직전 상장 0곳). "
                + "청약: 균등 최소 단위(보통 10주, 증거금 약 117,500원)만. 증권사 한 곳만. 상장일 시초가 매도. 청약 10/2(금)~10/6(화), 하나증권.",
                captor.getValue().message());
    }

    @Test
    void 확정가만_빈_회사는_청약이_먼_신규_공모에_밀리지_않고_먼저_읽는다() {
        // 10/15 실측: "확정 공모가 0개 회사" — 청약이 3~4주 뒤인 신규 공모가 회사 상한(10)을 다 써서, 지표는 읽었지만
        // 일정을 모르는 진코스텍 꼴 딜까지 차례가 오지 않았다
        LocalDate today = LocalDate.of(2026, 10, 15);
        IpoDealEntity jincostech = new IpoDealEntity("01158632", "진코스텍", "20261001000586", "DART", Instant.now());
        jincostech.classifyOffering(OfferingKind.IPO, Instant.now());
        jincostech.applyDemandForecast(new BigDecimal("1097.62"), new BigDecimal("0.0529"), "20260928000402", Instant.now());
        List<IpoDealEntity> all = new ArrayList<>();
        for (int i = 0; i < IpoDemandForecastCollector.CORPS_PER_RUN + 2; i++) {
            IpoDealEntity far = dealStarting("0900000" + (char) ('A' + i), "신규" + i, "2026101400010" + i,
                    LocalDate.of(2026, 11, 4), "10000");
            far.classifyOffering(OfferingKind.IPO, Instant.now());
            all.add(far);
        }
        all.add(jincostech);
        when(repository.findAll()).thenReturn(all);
        when(repository.findByCorpCode("01158632")).thenReturn(List.of(jincostech));
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(DartClient.RecentFilings.complete(List.of()));
        when(dartClient.fetchCorpEquityFilings(eq("01158632"), any(), any())).thenReturn(List.of(
                new DartClient.DealNotice("20260928000402", "01158632", "진코스텍", "[발행조건확정]증권신고서(지분증권)",
                        LocalDate.of(2026, 9, 28))));
        when(dartClient.fetchDocument("20260928000402")).thenReturn(Optional.of(DartFixtures.xml("20260928000402.zip")));

        IpoSyncScheduler.SyncSummary summary = schedulerAt(today).sync();

        assertEquals(new BigDecimal("23500"), jincostech.getOfferPriceFinal());
        assertEquals(1, summary.pricesFilled());
        // 청약이 10일보다 먼 회사는 확정 신고서를 찾지 않는다 — 진코스텍 한 곳만 조회
        verify(dartClient, times(1)).fetchCorpEquityFilings(any(), any(), any());
        // 확정 신고 9/28 → 10/15에는 청약이 끝났다: 수요예측 결과 알림은 없다
        var captor = org.mockito.ArgumentCaptor.forClass(IpoAlert.class);
        verify(publisher, org.mockito.Mockito.atLeast(0)).publishEvent(captor.capture());
        assertTrue(captor.getAllValues().stream().noneMatch(alert -> "METRICS".equals(alert.phase())),
                captor.getAllValues().toString());
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

    // ── 청약 추천(2026-10-15) — 시장 맥락은 판단 재료 표(V19)에서, 상장일 시세도 그 표에 ────────────────────

    /** 판단 재료 표 대역 — corp_code로 저장·조회. */
    private static IpoListingHistoryRepository historyBackedBy(java.util.Map<String, IpoListingHistoryEntity> rows) {
        IpoListingHistoryRepository repo = mock(IpoListingHistoryRepository.class);
        when(repo.findAll()).thenAnswer(inv -> List.copyOf(rows.values()));
        when(repo.findById(any())).thenAnswer(inv -> Optional.ofNullable(rows.get((String) inv.getArgument(0))));
        when(repo.save(any(IpoListingHistoryEntity.class))).thenAnswer(inv -> {
            IpoListingHistoryEntity row = inv.getArgument(0);
            rows.put(row.getCorpCode(), row);
            return row;
        });
        return repo;
    }

    /** 지난 공모주 한 곳 — 확정일·확약·상장일·종가 수익률. */
    private static IpoListingHistoryEntity past(String code, LocalDate confirmedOn, String lockup, LocalDate listedOn,
                                                String closeReturn) {
        IpoListingHistoryEntity row = new IpoListingHistoryEntity(code, "회사" + code, Instant.EPOCH);
        row.applyInputs("회사" + code, confirmedOn, new BigDecimal("800"), lockup == null ? null : new BigDecimal(lockup),
                new BigDecimal("10000"), null, null, null, Instant.EPOCH);
        if (listedOn != null) {
            BigDecimal close = new BigDecimal("10000").multiply(BigDecimal.ONE.add(new BigDecimal(closeReturn)));
            row.applyListing("9" + code.substring(1), listedOn, close, close, null, Instant.EPOCH);
        }
        return row;
    }

    @Test
    void 판단_시점_앞의_딜만으로_맥락을_세고_분위기가_나쁘면_패스한다() {
        // 니어스랩 꼴(10/15 실측 자료): 확정 2026-08-11, 743:1, 확약 1.26%, 상단, 유통 37.15% — 직전 상장 5곳이 줄줄이 공모가 하회
        java.util.Map<String, IpoListingHistoryEntity> rows = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 6; i++) {
            LocalDate confirmed = LocalDate.of(2026, 7, 1).plusDays(i * 5L);
            rows.put("0000000" + i, past("0000000" + i, confirmed, "0.10", confirmed.plusDays(10), "-0.20"));
        }
        // 판단 뒤에 상장한 곳(+300%)은 맥락에 들어가면 안 된다
        rows.put("00000099", past("00000099", LocalDate.of(2026, 8, 20), "0.90", LocalDate.of(2026, 8, 30), "3.0"));
        IpoListingHistoryRepository history = historyBackedBy(rows);
        IpoSyncScheduler scheduler = new IpoSyncScheduler(enabledProps, filterProps, dartClient, repository, alertLog, history,
                publisher, Clock.fixed(Instant.parse("2026-08-12T03:00:00Z"), ZoneOffset.UTC));
        IpoDealEntity deal = new IpoDealEntity("01234567", "니어스랩", "20260720000001", "DART", Instant.now());
        deal.classifyOffering(OfferingKind.IPO, Instant.now());
        deal.applyDemandForecast(new BigDecimal("743.49"), new BigDecimal("0.0126"), "20260811000001", Instant.now());
        deal.applyFinalPrice(new BigDecimal("41200"), new BigDecimal("35000"), new BigDecimal("41200"), Instant.now());
        deal.applyFloat(new BigDecimal("0.3715"), 5_771_485L, "20260810000140", Instant.now());

        scheduler.evaluateFilter(deal);

        assertEquals("PASS", deal.getAdviceGrade());
        assertEquals(IpoRecommendation.SKIP, deal.getRecommendation());
        assertEquals(-3, deal.getAdviceScore());   // +1 −1 0 −1 −2
        assertTrue(deal.getRecommendReason().contains("직전 상장 5곳 상장일 종가 평균 -20.0%"), deal.getRecommendReason());
        assertTrue(deal.getRecommendReason().contains("직전 6건 중앙값 10.00%의 절반 미만"), deal.getRecommendReason());
    }

    @Test
    void 수요예측_결과가_있는_공모주는_판단_재료를_표에_남긴다() {
        LocalDate today = LocalDate.of(2026, 10, 15);
        java.util.Map<String, IpoListingHistoryEntity> rows = new java.util.LinkedHashMap<>();
        IpoListingHistoryRepository history = historyBackedBy(rows);
        IpoDealEntity deal = jincostech();
        deal.applyDemandForecast(new BigDecimal("1097.62"), new BigDecimal("0.0529"), "20260928000402", Instant.now());
        deal.applyFinalPrice(new BigDecimal("23500"), null, null, Instant.now());
        IpoDealEntity rights = new IpoDealEntity("09999999", "상장사", "20261001000009", "DART", Instant.now());
        rights.classifyOffering(OfferingKind.RIGHTS, Instant.now());
        when(repository.findAll()).thenReturn(List.of(deal, rights));
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(DartClient.RecentFilings.complete(List.of()));
        Clock fixed = Clock.fixed(today.atTime(12, 0).toInstant(ZoneOffset.of("+09:00")), ZoneOffset.UTC);

        new IpoSyncScheduler(enabledProps, filterProps, dartClient, repository, alertLog, history, publisher, fixed).sync();

        assertEquals(List.of("01158632"), List.copyOf(rows.keySet()), "유상증자는 남기지 않는다");
        IpoListingHistoryEntity row = rows.get("01158632");
        assertEquals(LocalDate.of(2026, 9, 28), row.getConfirmedOn(), "판단 시점 = [발행조건확정] 접수일");
        assertEquals(new BigDecimal("1097.62"), row.getCompetitionRate());
        assertEquals(new BigDecimal("23500"), row.getOfferPrice());
        assertEquals("APP", row.getSource());
    }

    @Test
    void 상장일_시세를_받으면_종목코드로_맞춘_딜과_판단_재료_표에_남긴다() {
        java.util.Map<String, IpoListingHistoryEntity> rows = new java.util.LinkedHashMap<>();
        IpoListingHistoryRepository history = historyBackedBy(rows);
        IpoDealEntity deal = jincostech();
        deal.applyFinalPrice(new BigDecimal("12300"), null, null, Instant.now());
        deal.applyListing("179880", LocalDate.of(2026, 10, 15), Instant.now());
        when(repository.findAll()).thenReturn(List.of(deal));
        IpoSyncScheduler scheduler = new IpoSyncScheduler(enabledProps, filterProps, dartClient, repository, alertLog, history,
                publisher, Clock.systemUTC());

        scheduler.onListingDayPrices(new com.autostock.common.event.ListingDayPrices(LocalDate.of(2026, 10, 15), List.of(
                new com.autostock.common.event.ListingDayPrices.Entry("179880", "멜콘", LocalDate.of(2026, 10, 15),
                        new BigDecimal("27400"), new BigDecimal("28800"), new BigDecimal("15780"), new BigDecimal("16700")),
                new com.autostock.common.event.ListingDayPrices.Entry("000001", "모르는회사", LocalDate.of(2026, 10, 15),
                        BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN))));

        assertEquals(new BigDecimal("27400"), deal.getListingOpen());
        assertEquals(new BigDecimal("16700"), deal.getListingClose());
        IpoListingHistoryEntity row = rows.get("01158632");
        assertEquals(LocalDate.of(2026, 10, 15), row.getListingDate());
        assertEquals(0, new BigDecimal("0.357724").compareTo(row.closeReturn()), "종가 16,700 ÷ 공모가 12,300 − 1");
        assertEquals(1, rows.size(), "맞추지 못한 종목은 남기지 않는다");
    }
}

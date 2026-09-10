package com.autostock.ipo;

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
        IpoDealEntity entity = new IpoDealEntity("01359815", "한울반도체", "20260910000583", "DART");
        entity.applyMetrics(new BigDecimal("600"), new BigDecimal("0.25"));

        scheduler.evaluateFilter(entity);

        assertEquals(IpoRecommendation.RECOMMEND, entity.getRecommendation());
    }

    @Test
    void 임계치_미충족이면_SKIP() {
        IpoDealEntity entity = new IpoDealEntity("01158632", "진코스텍", "20260910000579", "DART");
        entity.applyMetrics(new BigDecimal("300"), new BigDecimal("0.25"));

        scheduler.evaluateFilter(entity);

        assertEquals(IpoRecommendation.SKIP, entity.getRecommendation());
    }

    @Test
    void 지표가_하나라도_없으면_PENDING이고_사유에_어떤_지표인지_명시한다() {
        IpoDealEntity entity = new IpoDealEntity("01359815", "한울반도체", "20260910000583", "DART");

        scheduler.evaluateFilter(entity);

        assertEquals(IpoRecommendation.PENDING, entity.getRecommendation());
        assertTrue(entity.getRecommendReason().contains("기관경쟁률"));
        assertTrue(entity.getRecommendReason().contains("의무보유확약비율"));
    }

    @Test
    void 경계값_정확히_임계치와_같으면_RECOMMEND() {
        IpoDealEntity entity = new IpoDealEntity("01359815", "한울반도체", "20260910000583", "DART");
        entity.applyMetrics(new BigDecimal("500"), new BigDecimal("0.20"));

        scheduler.evaluateFilter(entity);

        assertEquals(IpoRecommendation.RECOMMEND, entity.getRecommendation());
    }

    @Test
    void 청약시작_D_1에_알림을_발행한다() {
        LocalDate today = LocalDate.of(2026, 9, 11);
        IpoDealEntity entity = new IpoDealEntity("01359815", "한울반도체", "20260910000583", "DART");
        entity.applyOfferingDetail(new DartClient.OfferingDetail(
                "20260910000583", today.plusDays(1), today.plusDays(2), null, "SK증권",
                new BigDecimal("5030"), 3_800_000L));
        when(repository.findAll()).thenReturn(List.of(entity));
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(List.of());

        schedulerAt(today).syncNow();

        var captor = org.mockito.ArgumentCaptor.forClass(IpoAlert.class);
        verify(publisher, times(1)).publishEvent(captor.capture());
        assertEquals("D-1", captor.getValue().phase());
        assertEquals("한울반도체", captor.getValue().corpName());
    }

    @Test
    void 청약기간_중이_아니고_D_1_당일도_아니면_알림을_발행하지_않는다() {
        LocalDate today = LocalDate.of(2026, 9, 11);
        IpoDealEntity entity = new IpoDealEntity("01359815", "한울반도체", "20260910000583", "DART");
        entity.applyOfferingDetail(new DartClient.OfferingDetail(
                "20260910000583", today.plusDays(10), today.plusDays(11), null, "SK증권",
                new BigDecimal("5030"), 3_800_000L));
        when(repository.findAll()).thenReturn(List.of(entity));
        when(dartClient.fetchRecentEquityFilings(any(), any())).thenReturn(List.of());

        schedulerAt(today).syncNow();

        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void 상태_재계산_청약기간_중이면_SUBSCRIBING() {
        IpoDealEntity entity = new IpoDealEntity("01359815", "한울반도체", "20260910000583", "DART");
        LocalDate today = LocalDate.of(2026, 11, 9);
        entity.applyOfferingDetail(new DartClient.OfferingDetail(
                "20260910000583", LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 10), null, "SK증권",
                new BigDecimal("5030"), 3_800_000L));

        scheduler.recalculateStatus(entity, today);

        assertEquals(IpoStatus.SUBSCRIBING, entity.getStatus());
    }

    @Test
    void 상태_재계산_청약종료_이후면_PASSED() {
        IpoDealEntity entity = new IpoDealEntity("01359815", "한울반도체", "20260910000583", "DART");
        entity.applyOfferingDetail(new DartClient.OfferingDetail(
                "20260910000583", LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 10), null, "SK증권",
                new BigDecimal("5030"), 3_800_000L));

        scheduler.recalculateStatus(entity, LocalDate.of(2026, 11, 20));

        assertEquals(IpoStatus.PASSED, entity.getStatus());
    }

    @Test
    void 신규딜은_rcept_no_기준으로_upsert된다() {
        LocalDate since = LocalDate.of(2026, 8, 28);
        LocalDate today = LocalDate.of(2026, 9, 11);
        var notice = new DartClient.DealNotice("20260910000583", "01359815", "한울반도체",
                "[기재정정]증권신고서(지분증권)", LocalDate.of(2026, 9, 10));
        when(dartClient.fetchRecentEquityFilings(since, today)).thenReturn(List.of(notice));
        when(repository.findByRceptNo("20260910000583")).thenReturn(Optional.empty());
        when(dartClient.fetchOfferingDetail(eq("01359815"), eq("20260910000583"), any(), any()))
                .thenReturn(Optional.of(new DartClient.OfferingDetail(
                        "20260910000583", LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 10),
                        LocalDate.of(2026, 11, 17), "SK증권", new BigDecimal("5030"), 3_800_000L)));
        when(repository.findAll()).thenReturn(List.of());

        schedulerAt(today).syncNow();

        var captor = org.mockito.ArgumentCaptor.forClass(IpoDealEntity.class);
        verify(repository, times(1)).save(captor.capture());
        IpoDealEntity saved = captor.getValue();
        assertEquals("한울반도체", saved.getCorpName());
        assertEquals("SK증권", saved.getLeadManager());
        assertFalse(saved.getSubscriptionStart() == null);
    }

    /** 주어진 KST 날짜의 정오를 "오늘"로 보는 스케줄러 — Clock 주입으로 결정론적 테스트. */
    private IpoSyncScheduler schedulerAt(LocalDate today) {
        Clock fixed = Clock.fixed(today.atTime(12, 0).toInstant(ZoneOffset.of("+09:00")), ZoneOffset.UTC);
        return new IpoSyncScheduler(enabledProps, filterProps, dartClient, repository, publisher, fixed);
    }
}

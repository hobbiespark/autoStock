package com.autostock.macrointel;

import com.autostock.common.event.DisclosureRisk;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DisclosureBlacklistSyncScheduler — 파싱→DisclosureRisk 이벤트 발행, 종목코드 없는 건 스킵,
 * 만료일 계산(rcept_dt + retentionDays), enabled=false 스킵을 검증한다(PLAN.md ADR-14, 트랙 G1).
 * 실제 네트워크 호출은 하지 않는다(MajorDisclosureDartClient는 mock).
 */
class DisclosureBlacklistSyncSchedulerTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(LocalDate.of(2026, 9, 11).atTime(8, 35).toInstant(ZoneOffset.of("+09:00")), ZoneOffset.UTC);

    private final DisclosureBlacklistProperties enabledProps =
            new DisclosureBlacklistProperties(true, "test-key", 7, 180);
    private final MajorDisclosureDartClient dartClient = mock(MajorDisclosureDartClient.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

    private final DisclosureBlacklistSyncScheduler scheduler =
            new DisclosureBlacklistSyncScheduler(enabledProps, dartClient, publisher, FIXED_CLOCK);

    @Test
    void enabled가_false면_아무것도_하지_않는다() {
        DisclosureBlacklistSyncScheduler disabled = new DisclosureBlacklistSyncScheduler(
                new DisclosureBlacklistProperties(false, "", 7, 180), dartClient, publisher, FIXED_CLOCK);

        disabled.syncNow();

        verify(dartClient, never()).fetchRecentIssuanceDecisions(any(), any());
    }

    @Test
    void 종목코드가_있으면_DisclosureRisk_이벤트를_발행하고_만료일은_공시일_180일_후다() {
        var notice = new MajorDisclosureDartClient.MajorDisclosureNotice(
                "20260910000580", "00132868", "우성머티리얼스", "011300",
                DisclosureType.PAID_IN_CAPITAL_INCREASE, "주요사항보고서(유상증자결정)",
                LocalDate.of(2026, 9, 10));
        when(dartClient.fetchRecentIssuanceDecisions(any(), any())).thenReturn(List.of(notice));

        scheduler.syncNow();

        var captor = org.mockito.ArgumentCaptor.forClass(DisclosureRisk.class);
        verify(publisher).publishEvent(captor.capture());
        DisclosureRisk event = captor.getValue();
        assertEquals("011300", event.symbol());
        assertEquals("우성머티리얼스", event.corpName());
        assertEquals("PAID_IN_CAPITAL_INCREASE", event.disclosureType());
        assertEquals("20260910000580", event.rceptNo());
        assertEquals(LocalDate.of(2027, 3, 9), event.expiresOn(), "2026-09-10 + 180일");
    }

    @Test
    void 종목코드가_없으면_비상장이라_이벤트를_발행하지_않는다() {
        var notice = new MajorDisclosureDartClient.MajorDisclosureNotice(
                "20260910000581", "00000001", "비상장회사", null,
                DisclosureType.CONVERTIBLE_BOND, "주요사항보고서(전환사채권발행결정)",
                LocalDate.of(2026, 9, 10));
        when(dartClient.fetchRecentIssuanceDecisions(any(), any())).thenReturn(List.of(notice));

        scheduler.syncNow();

        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void 한_건의_이벤트_발행_실패가_다른_건_처리를_막지_않는다() {
        var bad = new MajorDisclosureDartClient.MajorDisclosureNotice(
                "r1", "c1", "실패건", "000001", DisclosureType.CONVERTIBLE_BOND, "x", null);
        var good = new MajorDisclosureDartClient.MajorDisclosureNotice(
                "r2", "c2", "정상건", "000002", DisclosureType.CONVERTIBLE_BOND, "x", LocalDate.of(2026, 9, 10));
        when(dartClient.fetchRecentIssuanceDecisions(any(), any())).thenReturn(List.of(bad, good));

        scheduler.syncNow();

        var captor = org.mockito.ArgumentCaptor.forClass(DisclosureRisk.class);
        verify(publisher).publishEvent(captor.capture());
        assertEquals("정상건", captor.getValue().corpName());
    }
}

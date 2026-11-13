package com.autostock.ipo;

import com.autostock.support.PostgresDataJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DART 거꾸로 채우기(VALUNIVO 875, D-29) — 실제 PostgreSQL(V35), DART는 대역. 시계 2026-11-13 KST. 값은 합성이다.
 * 마지막 시험은 채운 이력을 계좌 개설 계획기에 넘기는 조립 경로다.
 */
class IpoUnderwriterBackfillDbTest extends PostgresDataJpaTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-13T01:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 13);
    private static final DartProperties DART_ON = new DartProperties(true, "test-key", 14);

    @Autowired
    IpoUnderwritingHistoryRepository history;

    @Autowired
    IpoBackfillProgressRepository progress;

    private final DartClient dart = mock(DartClient.class);

    private IpoUnderwriterBackfill backfill(int windowsPerPass) {
        return new IpoUnderwriterBackfill(dart, DART_ON, new IpoBackfillProperties(true, 5, windowsPerPass), history, progress, CLOCK);
    }

    private static DartClient.DealNotice notice(String corp, String name, String rcept, String report, LocalDate on) {
        return new DartClient.DealNotice(rcept, corp, name, report, on);
    }

    private static DartClient.OfferingLookup lookup(String rcept, LocalDate start, OfferingKind kind, String... underwriters) {
        return new DartClient.OfferingLookup(new DartClient.OfferingDetail(rcept, start, start.plusDays(1), start.plusDays(3),
                underwriters.length == 0 ? null : underwriters[0], new BigDecimal("10000"), null, List.of(underwriters)), kind, "일반공모");
    }

    @Test
    void 첫_창에서_공모만_남기고_유상증자와_발행조건확정은_빼며_진행_위치를_옮긴다() {
        LocalDate until = TODAY;
        LocalDate since = TODAY.minusDays(13);
        when(dart.fetchRecentEquityFilings(since, until)).thenReturn(DartClient.RecentFilings.complete(List.of(
                notice("C1", "가나전자", "R1A", "[발행조건확정]증권신고서(지분증권)", TODAY.minusDays(2)),
                notice("C1", "가나전자", "R1B", "증권신고서(지분증권)", TODAY.minusDays(10)),
                notice("C2", "다라상사", "R2", "증권신고서(지분증권)", TODAY.minusDays(5)))));
        when(dart.fetchOffering(eq("C1"), eq("R1B"), any(), any()))
                .thenReturn(Optional.of(lookup("R1B", TODAY.plusDays(5), OfferingKind.IPO, "신영증권", "교보증권")));
        when(dart.fetchOffering(eq("C2"), eq("R2"), any(), any()))
                .thenReturn(Optional.of(lookup("R2", TODAY.plusDays(3), OfferingKind.RIGHTS, "미래에셋증권")));

        assertEquals(1, backfill(1).pass());

        assertEquals(1, history.count());
        IpoUnderwritingHistoryEntity saved = history.findByCorpCode("C1").orElseThrow();
        assertEquals(List.of("신영증권", "교보증권"), saved.getUnderwriterList());
        assertEquals(TODAY.plusDays(5), saved.countedOn());
        verify(dart, never()).fetchOffering(eq("C1"), eq("R1A"), any(), any());   // 발행조건확정 접수번호는 행이 없어 묻지 않음
        IpoBackfillProgressEntity state = progress.findById(IpoUnderwriterBackfill.PROGRESS_NAME).orElseThrow();
        assertEquals(since, state.getCoveredFrom());
        assertEquals(TODAY.minusYears(5), state.getTargetFrom());
    }

    @Test
    void 첫_접수번호가_비어도_다음_접수번호로_공모를_찾고_상세_조회는_창_끝까지만_묻는다() {
        // VALUNIVO 880: estkRs.json은 조회 기간의 최신 신고서만 돌려준다. 첫판은 창 끝 + 120일로 물어 이 창의 행이 없었고,
        // 첫 결과가 비면 회사를 통째로 건너뛰어 공모 대부분을 놓쳤다(운영 첫 실행: 신고 130건 중 3곳).
        LocalDate until = TODAY;
        LocalDate since = TODAY.minusDays(13);
        when(dart.fetchRecentEquityFilings(since, until)).thenReturn(DartClient.RecentFilings.complete(List.of(
                notice("C1", "가나전자", "R1NEW", "[기재정정]증권신고서(지분증권)", TODAY.minusDays(1)),
                notice("C1", "가나전자", "R1OLD", "증권신고서(지분증권)", TODAY.minusDays(9)),
                notice("C2", "다라상사", "R2", "증권신고서(지분증권)", TODAY.minusDays(4)),
                notice("C2", "다라상사", "R2B", "증권신고서(지분증권)", TODAY.minusDays(8)))));
        when(dart.fetchOffering(eq("C1"), eq("R1NEW"), any(), any())).thenReturn(Optional.empty());
        when(dart.fetchOffering(eq("C1"), eq("R1OLD"), any(), any()))
                .thenReturn(Optional.of(lookup("R1OLD", TODAY.plusDays(4), OfferingKind.IPO, "신영증권")));
        when(dart.fetchOffering(eq("C2"), eq("R2"), any(), any()))
                .thenReturn(Optional.of(lookup("R2", TODAY.plusDays(2), OfferingKind.RIGHTS, "미래에셋증권")));

        backfill(1).pass();

        assertEquals(List.of("신영증권"), history.findByCorpCode("C1").orElseThrow().getUnderwriterList(), "두 번째 접수번호로 찾음");
        assertFalse(history.findByCorpCode("C2").isPresent(), "유상증자는 남기지 않음");
        verify(dart).fetchOffering("C1", "R1OLD", since, until);   // 창 끝까지만 묻는다
        verify(dart, never()).fetchOffering(eq("C2"), eq("R2B"), any(), any());   // 유상증자로 판정되면 그 회사는 더 묻지 않는다
        assertEquals(IpoUnderwriterBackfill.PROGRESS_NAME, "underwriters-v2");
    }

    @Test
    void 목록을_끝까지_읽지_못하면_진행_위치를_옮기지_않고_다음_번에_같은_창을_다시_읽는다() {
        when(dart.fetchRecentEquityFilings(any(), any())).thenReturn(new DartClient.RecentFilings(List.of(), false, 3, 11));

        assertEquals(0, backfill(4).pass());
        assertEquals(TODAY.plusDays(1), progress.findById(IpoUnderwriterBackfill.PROGRESS_NAME).orElseThrow().getCoveredFrom());

        when(dart.fetchRecentEquityFilings(any(), any())).thenReturn(DartClient.RecentFilings.complete(List.of()));
        assertEquals(4, backfill(4).pass());
        assertEquals(TODAY.minusDays(55), progress.findById(IpoUnderwriterBackfill.PROGRESS_NAME).orElseThrow().getCoveredFrom(),
                "2주 창 4개 = 56일");
    }

    @Test
    void 목표_날짜에_닿으면_멈추고_이미_남긴_회사는_다시_조회하지_않는다() {
        when(dart.fetchRecentEquityFilings(any(), any())).thenReturn(DartClient.RecentFilings.complete(List.of(
                notice("C1", "가나전자", "R1", "증권신고서(지분증권)", TODAY.minusDays(1)))));
        when(dart.fetchOffering(eq("C1"), eq("R1"), any(), any()))
                .thenReturn(Optional.of(lookup("R1", TODAY, OfferingKind.IPO, "신영증권")));

        IpoUnderwriterBackfill job = backfill(26);
        int total = 0;
        for (int i = 0; i < 6; i++) {
            total += job.pass();
        }

        assertTrue(progress.findById(IpoUnderwriterBackfill.PROGRESS_NAME).orElseThrow().done());
        assertEquals(0, job.pass(), "끝나면 더 읽지 않는다");
        assertTrue(total >= 130 && total <= 131, "5년 = 2주 창 약 131개: " + total);
        verify(dart, org.mockito.Mockito.times(1)).fetchOffering(eq("C1"), eq("R1"), any(), any());
    }

    @Test
    void 채운_이력이_계좌_개설_추천의_1_3_5년_실적으로_이어진다() {
        when(dart.fetchRecentEquityFilings(any(), any())).thenReturn(DartClient.RecentFilings.complete(List.of()));
        when(dart.fetchRecentEquityFilings(TODAY.minusDays(13), TODAY)).thenReturn(DartClient.RecentFilings.complete(List.of(
                notice("C1", "가나전자", "R1", "증권신고서(지분증권)", TODAY.minusDays(3)))));
        when(dart.fetchOffering(eq("C1"), eq("R1"), any(), any()))
                .thenReturn(Optional.of(lookup("R1", TODAY.minusDays(1), OfferingKind.IPO, "신영증권")));
        backfill(2).pass();

        Set<String> held = BrokerNames.keys(List.of("KB증권"));
        LocalDate coveredFrom = progress.findById(IpoUnderwriterBackfill.PROGRESS_NAME).orElseThrow().getCoveredFrom();
        IpoAlertPlanner.PlannedAlert alert = IpoAccountOpenPlanner.plan(List.of(), history.findAll(), coveredFrom, held, TODAY, null)
                .orElseThrow();

        assertEquals("신영증권", alert.corpName());
        assertTrue(alert.message().contains("보유 계좌로 못 한 공모 인수: 최근 1년 1건 · 3년 1건 · 5년 1건(지난 공모 이력은 "
                + coveredFrom + "부터 채움, 5년 채우는 중)."), alert.message());
        assertFalse(alert.message().contains("다가오는 추천 딜"));
    }
}

package com.autostock.ipo;

import com.autostock.common.event.RecentListings;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IpoListingMatcher — 최근 상장 종목과 공모주 딜을 회사명·날짜로 맞춘다. 틀리게 붙이느니 비워 둔다(2026-10-13).
 */
class IpoListingMatcherTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    private final IpoDealRepository repository = mock(IpoDealRepository.class);
    private final IpoListingMatcher matcher = new IpoListingMatcher(repository, Clock.systemUTC());

    private static IpoDealEntity deal(String corpCode, String corpName, String rceptNo, LocalDate subscriptionEnd) {
        IpoDealEntity deal = new IpoDealEntity(corpCode, corpName, rceptNo, "DART", Instant.parse("2026-10-01T00:00:00Z"));
        if (subscriptionEnd != null) {
            deal.applyOfferingDetail(new DartClient.OfferingDetail(rceptNo, subscriptionEnd.minusDays(1), subscriptionEnd,
                    subscriptionEnd.plusDays(2), "대신증권", new BigDecimal("10000"), 1_000_000L), Instant.now());
        }
        deal.classifyOffering(OfferingKind.IPO, Instant.now());
        return deal;
    }

    private static RecentListings.Listing listing(String code, String name, LocalDate listedOn) {
        return new RecentListings.Listing(code, name, listedOn, "코스닥");
    }

    @Test
    void 회사명은_법인_표기_띄어쓰기를_빼고_기업인수목적을_스팩으로_숫자_앞_제를_빼서_견준다() {
        assertEquals(IpoListingMatcher.normalize("한화플러스6호스팩"), IpoListingMatcher.normalize("한화플러스제6호기업인수목적"));
        assertEquals(IpoListingMatcher.normalize("아이엠스팩1호"), IpoListingMatcher.normalize("아이엠기업인수목적1호 주식회사"));
        assertEquals("엘리스그룹", IpoListingMatcher.normalize("(주) 엘리스 그룹"));
        assertEquals("SIMWONAMERICACORP", IpoListingMatcher.normalize("SimwonAmericaCorp."));
    }

    @Test
    void 정정_신고서마다_있는_딜_모두에_종목코드와_상장일을_남긴다() {
        IpoDealEntity first = deal("01158632", "진코스텍", "20260910000579", LocalDate.of(2026, 10, 14));
        IpoDealEntity confirmed = deal("01158632", "진코스텍", "20261001000586", null);
        when(repository.findAll()).thenReturn(List.of(first, confirmed));

        int matched = matcher.apply(List.of(listing("250030", "진코스텍", TODAY)), TODAY);

        assertEquals(1, matched);
        assertEquals("250030", first.getStockCode());
        assertEquals("250030", confirmed.getStockCode());
        assertEquals(TODAY, confirmed.getListingDate());
        verify(repository, times(2)).save(any());
    }

    @Test
    void 청약_마감_전에_상장한_같은_이름_종목이나_이름이_같은_후보가_둘이면_붙이지_않는다() {
        IpoDealEntity early = deal("01111111", "가나다", "20261001000001", LocalDate.of(2026, 10, 20));
        IpoDealEntity twin = deal("02222222", "라마바", "20261001000002", LocalDate.of(2026, 10, 14));
        when(repository.findAll()).thenReturn(List.of(early, twin));

        int matched = matcher.apply(List.of(
                listing("111110", "가나다", LocalDate.of(2026, 10, 19)),   // 청약 마감(10/20) 전 상장 — 다른 회사
                listing("222220", "라마바", TODAY),
                listing("222230", "라 마 바", TODAY)), TODAY);              // 같은 이름 둘

        assertEquals(0, matched);
        assertNull(early.getStockCode());
        assertNull(twin.getStockCode());
        verify(repository, never()).save(any());
    }

    @Test
    void 사람이_넣은_상장일이_다르면_종목코드만_남기고_상장일은_그대로_둔다() {
        IpoDealEntity deal = deal("01158632", "진코스텍", "20260910000579", LocalDate.of(2026, 10, 14));
        deal.applyMetrics(null, null, LocalDate.of(2026, 10, 23), Instant.now());
        when(repository.findAll()).thenReturn(List.of(deal));

        matcher.apply(List.of(listing("250030", "진코스텍", TODAY)), TODAY);

        assertEquals("250030", deal.getStockCode());
        assertEquals(LocalDate.of(2026, 10, 23), deal.getListingDate());
    }

    @Test
    void 유상증자와_이미_맞춘_회사는_보지_않는다() {
        IpoDealEntity rights = deal("03333333", "상장사", "20261001000003", LocalDate.of(2026, 10, 14));
        rights.classifyOffering(OfferingKind.RIGHTS, Instant.now());
        IpoDealEntity done = deal("04444444", "끝난회사", "20261001000004", LocalDate.of(2026, 10, 14));
        done.applyListing("444440", LocalDate.of(2026, 10, 21), Instant.now());
        when(repository.findAll()).thenReturn(List.of(rights, done));

        int matched = matcher.apply(List.of(listing("333330", "상장사", TODAY), listing("444441", "끝난회사", TODAY)), TODAY);

        assertEquals(0, matched);
        assertNull(rights.getStockCode());
        assertEquals("444440", done.getStockCode());
    }
}

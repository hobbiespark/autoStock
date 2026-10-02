package com.autostock.monitor;

import com.autostock.common.event.DisclosureBlacklisted;
import com.autostock.common.util.StockCode;
import com.autostock.common.util.StockNames;
import com.autostock.portfolio.PositionBook;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 공시 블랙리스트 알림 요약(2026-10-01, aiDoc/alert-digest.md) — 매매 대상·보유 종목만 바로 보내고,
 * 나머지는 1분 창 요약 1건으로 보낸다.
 */
class DisclosureBlacklistListenerTest {

    private final List<NoticeLevel> levels = new ArrayList<>();
    private final List<String> sent = new ArrayList<>();
    private final Notifier notifier = (level, message) -> {
        levels.add(level);
        sent.add(message);
    };
    private final PositionBook positionBook = mock(PositionBook.class);
    /** 설정값처럼 공백·빈 항목이 섞여 들어와도 정리해서 쓴다. */
    private final DisclosureBlacklistListener listener =
            new DisclosureBlacklistListener(notifier, positionBook, List.of("005930", " 069500 ", ""));

    private static DisclosureBlacklisted event(String symbol, String corpName, String type,
                                               String rceptNo, LocalDate expiresOn) {
        return new DisclosureBlacklisted(symbol, corpName, type, rceptNo, expiresOn,
                Instant.parse("2026-09-30T22:15:56Z"));
    }

    private static DisclosureBlacklisted event(String symbol, String corpName, String type) {
        return event(symbol, corpName, type, "20260930000" + symbol.substring(3), LocalDate.of(2027, 3, 29));
    }

    @Test
    void 매매_대상_종목은_바로_WARN으로_보낸다() {
        listener.onDisclosureBlacklisted(event("069500", "KODEX 200", "유상증자 결정"));

        assertEquals(List.of(NoticeLevel.WARN), levels);
        assertTrue(sent.get(0).startsWith("매수 금지 등록(매매 대상): KODEX 200(069500) — 유상증자 결정"), sent.get(0));
    }

    @Test
    void 보유_종목은_매매_대상이_아니어도_바로_WARN으로_보낸다() {
        when(positionBook.holds(new StockCode("123456"))).thenReturn(true);

        listener.onDisclosureBlacklisted(event("123456", "보유회사", "전환사채(CB) 발행 결정"));

        assertEquals(List.of(NoticeLevel.WARN), levels);
        assertTrue(sent.get(0).startsWith("매수 금지 등록(보유 종목): 보유회사(123456)"), sent.get(0));
    }

    @Test
    void 보유이면서_매매_대상이면_둘_다_표시한다() {
        when(positionBook.holds(new StockCode("005930"))).thenReturn(true);

        listener.onDisclosureBlacklisted(event("005930", "삼성전자", "유상증자 결정"));

        assertTrue(sent.get(0).startsWith("매수 금지 등록(보유·매매 대상): 삼성전자(005930)"), sent.get(0));
    }

    @Test
    void 사전에_키움_종목명이_있으면_공시_기업명보다_먼저_쓰고_처음_보는_종목은_기업명을_배운다() {
        StockNames.learn("005930", "삼성전자", StockNames.Source.KIWOOM);
        when(positionBook.holds(new StockCode("123456"))).thenReturn(true);

        listener.onDisclosureBlacklisted(event("005930", "삼성전자(주)", "유상증자 결정"));
        listener.onDisclosureBlacklisted(event("123456", "보유회사", "전환사채(CB) 발행 결정"));

        assertTrue(sent.get(0).startsWith("매수 금지 등록(매매 대상): 삼성전자(005930) — "), sent.get(0));
        assertEquals("보유회사(123456)", StockNames.label("123456")); // 다른 알림·로그도 같은 이름을 쓴다
    }

    @Test
    void 그_밖의_종목은_바로_보내지_않고_요약_1건으로_보낸다() {
        listener.onDisclosureBlacklisted(event("013720", "청보", "전환사채(CB) 발행 결정",
                "20260930000878", LocalDate.of(2027, 3, 30)));
        listener.onDisclosureBlacklisted(event("032790", "엠젠솔루션", "유상증자 결정",
                "20260930000870", LocalDate.of(2027, 3, 30)));
        listener.onDisclosureBlacklisted(event("210120", "캔버스엔", "유상증자 결정",
                "20260930000604", LocalDate.of(2027, 3, 29)));
        listener.onDisclosureBlacklisted(event("210120", "캔버스엔", "전환사채(CB) 발행 결정",
                "20260930000642", LocalDate.of(2027, 3, 29)));
        assertTrue(sent.isEmpty(), "요약 시점 전에는 보내지 않는다");

        listener.flushDigest();

        assertEquals(List.of(NoticeLevel.INFO), levels);
        String digest = sent.get(0);
        assertTrue(digest.startsWith("공시 블랙리스트 신규 4건(3종목, 매매 대상·보유 종목 아님"), digest);
        assertTrue(digest.contains("유형: 전환사채(CB) 발행 결정 2, 유상증자 결정 2"), digest);
        assertTrue(digest.contains("종목: 청보(013720), 엠젠솔루션(032790), 캔버스엔(210120)"), digest);
        assertTrue(digest.contains("해제예정: 2027-03-29~2027-03-30"), digest);
    }

    @Test
    void 대기열이_비어_있으면_보내지_않고_보낸_뒤에는_비운다() {
        listener.flushDigest();
        assertTrue(sent.isEmpty());

        listener.onDisclosureBlacklisted(event("013720", "청보", "유상증자 결정"));
        listener.flushDigest();
        listener.flushDigest();

        assertEquals(1, sent.size());
        assertTrue(sent.get(0).contains("해제예정: 2027-03-29"), sent.get(0));
    }

    @Test
    void 종목이_많으면_30개까지만_이름을_싣고_나머지는_종목_수로_줄인다() {
        IntStream.range(0, 35).forEach(i -> listener.onDisclosureBlacklisted(
                event("%06d".formatted(100000 + i), "회사" + i, "유상증자 결정")));

        listener.flushDigest();

        String digest = sent.get(0);
        assertTrue(digest.startsWith("공시 블랙리스트 신규 35건(35종목"), digest);
        assertTrue(digest.contains("회사29(100029)"), digest);
        assertFalse(digest.contains("회사30(100030)"), digest);
        assertTrue(digest.contains("외 5종목"), digest);
    }

    @Test
    void 종료할_때_남은_대기열을_보낸다() {
        listener.onDisclosureBlacklisted(event("013720", "청보", "유상증자 결정"));

        listener.flushOnShutdown();

        assertEquals(1, sent.size());
    }
}

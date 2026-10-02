package com.autostock.market;

import com.autostock.common.event.Candle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MinuteBarArchiver — 분봉을 DB에 쌓는 규칙(aiDoc/minute-bars-db.md): 장외 1년 되채우기, 겹치면 멈추는 따라잡기,
 * 진행 중인 분·검증 실패 행 제외, 장중 페이지 상한과 빈 구간 메우기, 일봉 집계 구간, 예전 CSV 이관.
 * 저장소는 (종목, 시각) 중복을 건너뛰는 메모리 대역, 시세는 페이지를 넘겨 주는 대역이다.
 */
class MinuteBarArchiverTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @TempDir
    Path dir;

    private final FakeStore store = new FakeStore();
    private final FakePort port = new FakePort();
    private final MarketSessionService session = mock(MarketSessionService.class);
    private final MutableClock clock = new MutableClock(kst(2026, 10, 3, 19, 0));   // 토요일 저녁 — 장외

    private MinuteBarArchiver archiver(String symbols) {
        return new MinuteBarArchiver(port, store, session, true, symbols, dir.toString(), clock);
    }

    @Test
    void 장외_첫_동기화는_브로커가_주는_끝까지_되채우고_전_구간_일봉을_다시_집계한다() {
        when(session.isActive()).thenReturn(false);
        port.pages("005930",
                minutes(LocalDateTime.of(2026, 10, 2, 15, 28), 3),
                minutes(LocalDateTime.of(2026, 10, 1, 15, 28), 3),
                minutes(LocalDateTime.of(2026, 9, 30, 15, 28), 3));
        MinuteBarArchiver archiver = archiver("005930");

        archiver.sync("기동");

        assertEquals(3, port.calls("005930"));
        assertEquals(9, store.size("005930"));
        assertEquals(List.of(range(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 3))), store.refreshed);

        // 다음 동기화는 따라잡기 — 첫 페이지가 DB의 최신 분봉과 겹쳐 바로 멈추고, 넣은 게 없어 집계도 하지 않는다
        archiver.sync("시간별 점검");
        assertEquals(4, port.calls("005930"));
        assertEquals(1, store.refreshed.size());
    }

    @Test
    void 정기_적재는_DB의_최신_분봉과_겹치는_페이지에서_멈추고_그날을_마친_것으로_본다() {
        when(session.isActive()).thenReturn(true);   // 거래일 15:45는 ACTIVE 창 안
        clock.set(kst(2026, 10, 2, 15, 45));
        store.put("005930", minutes(LocalDateTime.of(2025, 9, 1, 9, 0), 1));   // 1년 전부터 있다 — 되채우기 끝남
        store.put("005930", minutes(LocalDateTime.of(2026, 10, 1, 15, 28), 3));
        port.pages("005930",
                minutes(LocalDateTime.of(2026, 10, 2, 15, 28), 3),
                minutes(LocalDateTime.of(2026, 10, 1, 15, 28), 3),
                minutes(LocalDateTime.of(2026, 9, 30, 15, 28), 3));
        MinuteBarArchiver archiver = archiver("005930");

        archiver.archiveToday();

        assertEquals(2, port.calls("005930"));   // 둘째 페이지에서 겹침 — 셋째는 받지 않는다
        assertEquals(7, store.size("005930"));
        assertEquals(List.of(range(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 3))), store.refreshed);

        // 15:35 뒤 실패 없이 마쳤으니 매시 점검은 아무것도 하지 않는다
        when(session.isActive()).thenReturn(false);
        clock.set(kst(2026, 10, 2, 16, 15));
        archiver.hourlyCheck();
        assertEquals(2, port.calls("005930"));
    }

    @Test
    void 장중에는_진행_중인_분을_빼고_오늘_페이지만_넣고_되채우기는_장외_점검으로_미룬다() {
        when(session.isActive()).thenReturn(true);
        clock.set(kst(2026, 10, 6, 10, 30).plusSeconds(20));
        port.pages("005930",
                minutes(LocalDateTime.of(2026, 10, 6, 10, 28), 3),   // 10:30 분은 10:31에 끝난다 — 아직 넣지 않는다
                minutes(LocalDateTime.of(2026, 10, 2, 15, 28), 3),
                minutes(LocalDateTime.of(2026, 10, 1, 15, 28), 3));
        MinuteBarArchiver archiver = archiver("005930");

        archiver.sync("기동");

        assertEquals(1, port.calls("005930"));
        assertEquals(2, store.size("005930"));
        assertFalse(store.has("005930", LocalDateTime.of(2026, 10, 6, 10, 30)));

        when(session.isActive()).thenReturn(false);
        clock.set(kst(2026, 10, 6, 16, 15));
        archiver.hourlyCheck();

        assertEquals(4, port.calls("005930"));   // 처음부터 끝까지 다시 넘긴다(겹치는 행은 건너뜀)
        assertEquals(9, store.size("005930"));
        assertTrue(store.has("005930", LocalDateTime.of(2026, 10, 6, 10, 30)));
        assertEquals(range(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7)), store.refreshed.get(store.refreshed.size() - 1));
    }

    @Test
    void V12_CHECK와_같은_규칙에_걸린_행은_넣지_않고_센다() {
        when(session.isActive()).thenReturn(false);
        LocalDateTime t = LocalDateTime.of(2026, 10, 2, 15, 0);
        port.pages("005930", List.of(
                bar(t, "258000", "258500", "257500", "258000", 100, 1000),
                bar(t.plusMinutes(1), "258000", "258500", "258600", "258000", 100, 1100),   // 저가 > 종가
                bar(t.plusMinutes(2), "258000.5", "258500", "257500", "258000", 100, 1200), // 소수 가격
                bar(t.plusMinutes(3), "0", "0", "0", "0", 0, 1200)));                        // 체결 없는 분 — 넣는다
        MinuteBarArchiver archiver = archiver("005930");

        MinuteBarArchiver.SymbolResult result = archiver.syncSymbol("005930", true, clock.zoned());

        assertEquals(2, result.added());
        assertEquals(2, result.invalid());
        assertTrue(result.toLogLine().contains("검증 제외 2행"));
    }

    @Test
    void 장중_따라잡기가_페이지_상한까지_겹치지_않으면_빈_구간으로_남기고_장외에_그_아래_끝까지_메운다() {
        store.put("005930", minutes(LocalDateTime.of(2025, 9, 1, 9, 0), 1));
        store.put("005930", minutes(LocalDateTime.of(2026, 9, 20, 15, 30), 1));   // 마지막 적재 — 2주 넘게 꺼져 있었다
        List<List<MarketDataPort.MinuteBar>> pages = new ArrayList<>();
        for (int i = 0; i < 20; i++) {   // 하루에 한 행씩, 10/5부터 과거로 — 16번째 페이지(9/20 15:00)에서 겹친다
            pages.add(minutes(LocalDateTime.of(2026, 10, 5, 15, 0).minusDays(i), 1));
        }
        port.pages("005930", pages);
        when(session.isActive()).thenReturn(true);
        clock.set(kst(2026, 10, 6, 10, 30));
        MinuteBarArchiver archiver = archiver("005930");

        MinuteBarArchiver.SymbolResult first = archiver.syncSymbol("005930", false, clock.zoned());

        assertEquals(MinuteBarArchiver.CATCH_UP_MAX_PAGES, port.calls("005930"));
        assertTrue(first.gapLeft());
        assertTrue(first.toLogLine().contains("빈 구간 남음"));

        when(session.isActive()).thenReturn(false);
        clock.set(kst(2026, 10, 6, 16, 15));
        archiver.hourlyCheck();

        assertEquals(MinuteBarArchiver.CATCH_UP_MAX_PAGES + 16, port.calls("005930"));
        assertTrue(store.has("005930", LocalDateTime.of(2026, 9, 21, 15, 0)));   // 13~15번째 페이지 — 빈 구간이었다
        assertTrue(store.has("005930", LocalDateTime.of(2026, 9, 20, 15, 0)));
        List<Instant> refreshed = store.refreshed.get(store.refreshed.size() - 1);
        assertEquals(LocalDate.of(2026, 9, 20).atStartOfDay(KST).toInstant(), refreshed.get(0));   // 빈 구간 아래 끝부터 다시 집계

        archiver.hourlyCheck();   // 다 메웠다 — 더 하지 않는다
        assertEquals(MinuteBarArchiver.CATCH_UP_MAX_PAGES + 16, port.calls("005930"));
    }

    @Test
    void 한_종목이_실패해도_나머지는_넣고_실패한_종목은_장외_점검에서_다시_한다() {
        when(session.isActive()).thenReturn(false);
        store.put("000660", minutes(LocalDateTime.of(2025, 9, 1, 9, 0), 1));
        store.put("000660", minutes(LocalDateTime.of(2026, 10, 1, 15, 30), 1));
        port.pages("005930", minutes(LocalDateTime.of(2026, 10, 2, 15, 28), 3));
        port.pages("000660", minutes(LocalDateTime.of(2026, 10, 2, 15, 28), 3), minutes(LocalDateTime.of(2026, 10, 1, 15, 29), 2));
        port.failOnce("000660");
        MinuteBarArchiver archiver = archiver("000660,005930");

        archiver.sync("기동");

        assertEquals(3, store.size("005930"));
        assertEquals(2, store.size("000660"));   // 그대로

        archiver.hourlyCheck();   // 실패한 종목이 빈 구간으로 남아 장외 점검이 다시 돈다
        assertEquals(6, store.size("000660"));   // 10/2 3행 + 10/1 15:29(15:30은 이미 있음)
    }

    @Test
    void 예전_CSV는_첫_동기화_때_한_번만_옮기고_파일은_그대로_둔다() throws IOException {
        when(session.isActive()).thenReturn(false);
        store.put("005930", minutes(LocalDateTime.of(2025, 9, 1, 9, 0), 1));
        Path csv = dir.resolve("005930.csv");
        Files.writeString(csv, String.join("\n",
                "time,open,high,low,close,volume,acc_volume",
                "20260923090000,258000,258500,257500,258000,30,30",
                "20260923090100,258000,258100,258000,258100,20,50",
                "깨진 줄",
                "") , StandardCharsets.UTF_8);
        port.pages("005930", List.of());   // 브로커는 빈 페이지 — CSV 효과만 본다
        MinuteBarArchiver archiver = archiver("005930");

        archiver.sync("기동");
        assertEquals(3, store.size("005930"));
        assertTrue(store.has("005930", LocalDateTime.of(2026, 9, 23, 9, 1)));

        Files.writeString(csv, "20260923090200,258100,258100,258100,258100,10,60\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        archiver.sync("시간별 점검");
        assertEquals(3, store.size("005930"));   // 두 번째부터는 읽지 않는다
        assertTrue(Files.exists(csv));
    }

    @Test
    void 꺼져_있으면_아무것도_하지_않는다() {
        MinuteBarArchiver archiver = new MinuteBarArchiver(port, store, session, false, "005930", dir.toString(), clock);

        archiver.sync("기동");
        archiver.hourlyCheck();
        archiver.archiveToday();

        assertEquals(0, port.calls("005930"));
    }

    @Test
    void CSV_한_줄을_분_시작_시각과_원_단위_정수로_읽는다() {
        MinuteBarStore.Row row = MinuteBarArchiver.parseCsvLine("20260930090100,258000,258500,257500,258100,30,3400");

        assertNotNull(row);
        assertEquals(LocalDateTime.of(2026, 9, 30, 9, 1).atZone(KST).toInstant(), row.barTime());
        assertEquals(257500, row.low());
        assertEquals(3400L, row.accVolume());
        assertNull(MinuteBarArchiver.parseCsvLine("time,open,high,low,close,volume,acc_volume"));
        assertNull(MinuteBarArchiver.parseCsvLine("20260930090100,258000,258500"));
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private static Instant kst(int y, int m, int d, int h, int min) {
        return LocalDateTime.of(y, m, d, h, min).atZone(KST).toInstant();
    }

    private static List<Instant> range(LocalDate fromDay, LocalDate toDayExclusive) {
        return List.of(fromDay.atStartOfDay(KST).toInstant(), toDayExclusive.atStartOfDay(KST).toInstant());
    }

    /** from부터 1분 간격 count개 — 브로커처럼 최신이 앞에 오게 돌려준다. */
    private static List<MarketDataPort.MinuteBar> minutes(LocalDateTime from, int count) {
        List<MarketDataPort.MinuteBar> bars = new ArrayList<>();
        for (int i = count - 1; i >= 0; i--) {
            bars.add(bar(from.plusMinutes(i), "258000", "258500", "257500", "258100", 10 + i, 100 + i));
        }
        return bars;
    }

    private static MarketDataPort.MinuteBar bar(LocalDateTime time, String open, String high, String low, String close,
                                                long volume, long acc) {
        return new MarketDataPort.MinuteBar(time, new BigDecimal(open), new BigDecimal(high), new BigDecimal(low),
                new BigDecimal(close), volume, acc);
    }

    /** (종목, 시각)이 겹치면 건너뛰는 메모리 저장소 — ON CONFLICT DO NOTHING 대역. */
    private static final class FakeStore extends MinuteBarStore {
        final Map<String, TreeMap<Instant, Row>> rows = new HashMap<>();
        final List<List<Instant>> refreshed = new ArrayList<>();

        FakeStore() {
            super(null);
        }

        void put(String symbol, List<MarketDataPort.MinuteBar> bars) {
            insert(symbol, bars.stream().map(MinuteBarArchiver::toRow).toList());
        }

        int size(String symbol) {
            return rows.getOrDefault(symbol, new TreeMap<>()).size();
        }

        boolean has(String symbol, LocalDateTime time) {
            return rows.getOrDefault(symbol, new TreeMap<>()).containsKey(time.atZone(KST).toInstant());
        }

        @Override
        public int insert(String symbol, List<Row> list) {
            TreeMap<Instant, Row> map = rows.computeIfAbsent(symbol, k -> new TreeMap<>());
            int added = 0;
            for (Row row : list) {
                if (map.putIfAbsent(row.barTime(), row) == null) {
                    added++;
                }
            }
            return added;
        }

        @Override
        public Optional<Instant> latestBarTime(String symbol) {
            TreeMap<Instant, Row> map = rows.get(symbol);
            return map == null || map.isEmpty() ? Optional.empty() : Optional.of(map.lastKey());
        }

        @Override
        public Optional<Instant> earliestBarTime(String symbol) {
            TreeMap<Instant, Row> map = rows.get(symbol);
            return map == null || map.isEmpty() ? Optional.empty() : Optional.of(map.firstKey());
        }

        @Override
        public void refreshDailyBars(Instant from, Instant to) {
            refreshed.add(List.of(from, to));
        }
    }

    /** 종목마다 정해 둔 페이지를 키 "p1", "p2" …로 넘겨 주는 시세 대역. */
    private static final class FakePort implements MarketDataPort {
        private final Map<String, List<List<MinuteBar>>> pages = new HashMap<>();
        private final Map<String, Integer> calls = new HashMap<>();
        private final Set<String> failOnce = new HashSet<>();

        @SafeVarargs
        final void pages(String symbol, List<MinuteBar>... list) {
            pages.put(symbol, List.of(list));
        }

        void pages(String symbol, List<List<MinuteBar>> list) {
            pages.put(symbol, list);
        }

        void failOnce(String symbol) {
            failOnce.add(symbol);
        }

        int calls(String symbol) {
            return calls.getOrDefault(symbol, 0);
        }

        @Override
        public MinuteBarPage minuteBarPage(String symbol, String nextKey) {
            calls.merge(symbol, 1, Integer::sum);
            if (failOnce.remove(symbol)) {
                throw new IllegalStateException("키움 API 응답 없음(테스트)");
            }
            List<List<MinuteBar>> list = pages.getOrDefault(symbol, List.of());
            int index = nextKey == null ? 0 : Integer.parseInt(nextKey.substring(1));
            if (index >= list.size()) {
                return new MinuteBarPage(List.of(), null);
            }
            return new MinuteBarPage(list.get(index), index + 1 < list.size() ? "p" + (index + 1) : null);
        }

        @Override
        public StockQuote stockQuote(String symbol) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BestQuote bestQuote(String symbol) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Candle> dailyCandles(String symbol, LocalDate baseDate) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        java.time.ZonedDateTime zoned() {
            return now.atZone(KST);
        }

        @Override
        public ZoneId getZone() {
            return KST;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

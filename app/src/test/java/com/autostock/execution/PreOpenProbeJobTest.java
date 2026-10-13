package com.autostock.execution;

import com.autostock.common.event.TrProbeCompleted;
import com.autostock.kiwoom.KiwoomApiException;
import com.autostock.kiwoom.KiwoomRestClient;
import com.autostock.kiwoom.TrId;
import com.autostock.market.MarketCalendarService;
import com.autostock.market.MarketDataPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 장전 키움 응답 실측 — 거래일 07:30~08:40 5분마다 조회 TR 응답 코드·걸린 시간, 전 거래일 분봉 범위, 08:40에 하루 요약.
 */
class PreOpenProbeJobTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 12);   // 월요일 — 전 거래일은 10/8(10/9 한글날 휴장)

    @TempDir
    Path dir;

    private final KiwoomRestClient client = mock(KiwoomRestClient.class);
    private final MarketDataPort marketData = mock(MarketDataPort.class);
    private final MarketCalendarService calendar = mock(MarketCalendarService.class);
    private final List<Object> published = new ArrayList<>();
    private final MutableClock clock = new MutableClock(DAY.atTime(7, 30).atZone(KST).toInstant());

    @BeforeEach
    void setUp() {
        when(calendar.isTradingDay(any())).thenAnswer(inv -> {
            LocalDate d = inv.getArgument(0);
            return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY
                    && !d.equals(LocalDate.of(2026, 10, 9));
        });
        when(client.call(any(), anyString(), anyMap())).thenReturn(Map.of("return_code", 0));
        when(marketData.minuteBarPage(anyString(), isNull())).thenReturn(new MarketDataPort.MinuteBarPage(List.of(
                bar(LocalDateTime.of(2026, 10, 8, 9, 0)), bar(LocalDateTime.of(2026, 10, 8, 15, 30)),
                bar(LocalDateTime.of(2026, 10, 8, 19, 59)), bar(LocalDateTime.of(2026, 10, 7, 15, 30))), "p1"));
    }

    private static MarketDataPort.MinuteBar bar(LocalDateTime time) {
        BigDecimal price = new BigDecimal("108000");
        return new MarketDataPort.MinuteBar(time, price, price, price, price, 10, 10);
    }

    private PreOpenProbeJob job(boolean enabled) {
        return new PreOpenProbeJob(new PreOpenProbeProperties(enabled, LocalTime.of(7, 30), LocalTime.of(8, 40),
                List.of("005930", "069500"), dir.toString()), client, marketData, calendar, new ObjectMapper(), published::add, clock);
    }

    private void at(int hour, int minute, int second) {
        clock.set(DAY.atTime(hour, minute, second).atZone(KST).toInstant());
    }

    private List<String> lines() throws Exception {
        return Files.readAllLines(dir.resolve("pre_open_20261012.jsonl"), StandardCharsets.UTF_8);
    }

    @Test
    void 창_안의_틱마다_시세_잔고_분봉을_재고_마지막_틱에_오류_구간을_요약해_알린다() throws Exception {
        PreOpenProbeJob job = job(true);
        when(marketData.minuteBarPage(eq("005930"), isNull()))
                .thenThrow(new KiwoomApiException("키움 API 논리 오류 [ka10080] 서비스를 처리하는 중에 오류가 발생했습니다[1632]"))
                .thenReturn(new MarketDataPort.MinuteBarPage(List.of(bar(LocalDateTime.of(2026, 10, 8, 19, 59))), null));

        at(7, 30, 0);
        job.tick();
        at(7, 34, 57);   // 시계 보정으로 3초 일찍 — 07:35 틱으로 본다
        job.tick();
        at(8, 40, 0);
        job.tick();

        List<String> lines = lines();
        assertEquals(12, lines.size());   // 틱 3번 × (시세·잔고·분봉 2종목)
        assertTrue(lines.get(2).contains("\"code\":\"1632\""), lines.get(2));
        assertTrue(lines.get(6).contains("\"slot\":\"07:35\""), lines.get(6));
        verify(client, times(3)).call(eq(TrId.STOCK_PRICE), eq("/api/dostk/stkinfo"), eq(Map.of("stk_cd", "005930")));
        verify(client, times(3)).call(eq(TrId.ACCOUNT_BALANCE), eq("/api/dostk/acnt"), anyMap());

        TrProbeCompleted done = (TrProbeCompleted) published.get(0);
        assertTrue(done.success());
        assertTrue(done.summary().contains("12회 중 오류 1회(ka10080 1632 07:30~07:30 1회)"), done.summary());
        assertTrue(Files.exists(dir.resolve("pre_open_20261012_summary.json")));
    }

    @Test
    void 전_거래일_분봉은_종목마다_하루_한_번_개수와_시각_범위와_정규장_밖_수를_잰다() throws Exception {
        PreOpenProbeJob job = job(true);

        at(7, 30, 0);
        job.tick();
        at(7, 35, 0);
        job.tick();

        List<String> lines = lines();
        // 첫 틱: 휴장 10/9를 건너뛴 전 거래일 10/8 — 3개, 09:00~19:59, 정규장 밖 1개(19:59)
        assertTrue(lines.get(2).contains("\"previousDay\":{\"date\":\"2026-10-08\",\"bars\":3,\"first\":\"09:00\",\"last\":\"19:59\","
                + "\"outsideRegular\":1}"), lines.get(2));
        assertTrue(lines.get(2).contains("\"latest\":\"2026-10-08 19:59\""), lines.get(2));
        assertFalse(lines.get(6).contains("previousDay"), lines.get(6));   // 두 번째 틱에는 다시 재지 않는다
    }

    @Test
    void 오류가_없으면_요약은_남기되_알리지_않는다() throws Exception {
        PreOpenProbeJob job = job(true);

        at(8, 40, 0);
        job.tick();

        assertTrue(published.isEmpty());
        assertTrue(Files.readString(dir.resolve("pre_open_20261012_summary.json")).contains("오류 0회"));
    }

    @Test
    void 창_밖이거나_휴장일이거나_꺼져_있으면_재지_않는다() {
        PreOpenProbeJob job = job(true);
        at(7, 25, 0);
        job.tick();
        at(8, 45, 0);
        job.tick();
        clock.set(LocalDate.of(2026, 10, 9).atTime(8, 0).atZone(KST).toInstant());   // 한글날
        job.tick();
        at(8, 0, 0);
        job(false).tick();

        verifyNoInteractions(client, marketData);
    }

    @Test
    void 오류_코드는_메시지_안의_숫자_4자리만_본다() {
        assertEquals("1632", PreOpenProbeJob.errorCode("키움 API 논리 오류 [ka10080] 서비스를 처리하는 중에 오류가 발생했습니다[1632]"));
        assertEquals("8005", PreOpenProbeJob.errorCode("[8005:Token이 유효하지 않습니다]"));
        assertEquals("?", PreOpenProbeJob.errorCode("connection reset"));
        assertEquals("?", PreOpenProbeJob.errorCode(null));
    }

    @Test
    void 전_거래일_분봉_통계() {
        Map<String, Object> stats = PreOpenProbeJob.previousDayStats(List.of(
                bar(LocalDateTime.of(2026, 10, 8, 8, 0)), bar(LocalDateTime.of(2026, 10, 8, 9, 0)),
                bar(LocalDateTime.of(2026, 10, 8, 15, 35)), bar(LocalDateTime.of(2026, 10, 12, 8, 1))),
                LocalDate.of(2026, 10, 8));

        assertEquals(3, stats.get("bars"));
        assertEquals("08:00", stats.get("first"));
        assertEquals("15:35", stats.get("last"));
        assertEquals(2L, stats.get("outsideRegular"));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /** 같은 시각을 가리키되 시간대만 바꾼 시계 — 작업은 {@code clock.withZone(KST)}로 KST 시각을 본다. */
        @Override
        public Clock withZone(ZoneId zone) {
            MutableClock outer = this;
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return zone;
                }

                @Override
                public Clock withZone(ZoneId other) {
                    return outer.withZone(other);
                }

                @Override
                public Instant instant() {
                    return outer.instant();
                }
            };
        }
    }
}

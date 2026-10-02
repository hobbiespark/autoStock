package com.autostock.market;

import com.autostock.common.util.MarketConstants;
import com.autostock.common.util.StockNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 분봉 적재 잡 — TimescaleDB 하이퍼테이블 {@code minute_bars}(V12)에 쌓는다
 * (2026-09-11 신설 — ka10080 깊이 실측의 직접 후속. 2026-10-02 CSV에서 DB로, aiDoc/minute-bars-db.md).
 *
 * <p><b>왜 필요한가</b>: ka10080은 <b>최근 1년 롤링 창</b>만 제공함이 실측으로 확정됐다
 * (005930 1분봉 95,930행, 2025-09-01까지 — PROGRESS §2). 다년치 분봉은 지금부터 쌓는 것만이 유일한 경로다.
 *
 * <p><b>동기화 한 번</b>: 종목마다 ka10080을 최신 페이지부터 과거로 넘기며 넣는다({@code ON CONFLICT DO NOTHING} — 몇 번 돌려도 같다).
 * <ul>
 *   <li><b>따라잡기</b>: DB의 최신 분봉과 겹치는 페이지에서 멈춘다. 매일이면 1페이지(약 2.3거래일)로 끝난다. 앱이 며칠 꺼져 있었으면
 *       그만큼 더 넘긴다. 장중에는 {@value #CATCH_UP_MAX_PAGES}페이지까지만 — 남은 빈 구간은 다음 장외 되채우기가 메운다.</li>
 *   <li><b>되채우기</b>: 가장 오래된 분봉이 {@link #BACKFILL_COMPLETE_AGE}보다 최근이면(처음 켰을 때·중간에 끊겼을 때)
 *       브로커가 주는 끝(약 1년, 종목당 약 107페이지)까지 간다. <b>장외(STANDBY)에만</b> 한다 — 장중에 유량을 쓰지 않는다.</li>
 *   <li>끝난 분(분 시작 + 1분 ≤ 지금)만 넣는다 — 장중에 진행 중인 분의 값이 굳지 않게.</li>
 *   <li>넣은 구간의 일봉 연속 집계({@code daily_bars})를 다시 계산한다 — 정책(V12)은 최근 10일만 보므로 되채운 과거는 직접.</li>
 *   <li>예전 CSV({@code data/minutes/{종목}.csv})는 기동 뒤 첫 동기화 때 한 번 DB로 옮긴다. 파일은 그대로 둔다.</li>
 * </ul>
 *
 * <p><b>언제</b>: 평일 15:45 KST(장 마감 뒤, 15:50 리포트 전) 정기 적재 · 기동 직후 한 번(가상 스레드 — 되채우기가 길어도
 * 기동을 막지 않는다) · 매시 15분 점검(장외에 되채우기가 남았거나, 15:35 뒤 그날 동기화를 못 했으면 다시 한다).
 * 분봉 조회와 브로커 응답 번역(ka10080, 연속 조회 cont-yn·next-key)은 {@link MarketDataPort} 구현체가 맡는다.
 */
@Component
public class MinuteBarArchiver {

    private static final Logger log = LoggerFactory.getLogger(MinuteBarArchiver.class);

    /** 장중 따라잡기 페이지 상한 — 약 1개월. 넘치면 다음 장외에 끝까지 되채운다. */
    static final int CATCH_UP_MAX_PAGES = 12;
    /** 되채우기 페이지 상한 — 1년치(약 107페이지)에 여유를 둔다. 브로커가 끝없이 다음 키를 줘도 멈춘다. */
    static final int BACKFILL_MAX_PAGES = 150;
    /** 가장 오래된 분봉이 이보다 오래됐으면 1년 되채우기를 마친 것으로 본다(브로커 창은 약 1년). */
    static final Duration BACKFILL_COMPLETE_AGE = Duration.ofDays(330);
    /** 이 시각 뒤에 끝난 동기화는 그날 분봉을 다 담는다(정규장 15:30 + 여유). */
    static final LocalTime CLOSE_SETTLED = LocalTime.of(15, 35);
    /** CSV 이관 한 번에 넣는 행 수. */
    private static final int CSV_BATCH = 1000;
    /** 예전 CSV 첫 컬럼 형식(2026-09-11~10-02 적재분). */
    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    private final MarketDataPort marketData;
    private final MinuteBarStore store;
    private final MarketSessionService session;
    private final boolean enabled;
    private final List<String> symbols;
    private final Path csvDir;
    private final Clock clock;

    /** 동기화가 겹치지 않게 — 기동·정기·매시 점검이 같은 때 들어와도 하나만 돈다. */
    private final AtomicBoolean running = new AtomicBoolean();
    /** 이 JVM에서 되채우기를 마쳤거나 필요 없다고 확인한 종목. */
    private final Set<String> backfillDone = ConcurrentHashMap.newKeySet();
    /**
     * 빈 구간이 남은 종목과 그 아래 끝(그 시각의 분봉은 DB에 있다) — 장중 따라잡기가 페이지 상한에 걸렸거나 도중에 실패했을 때.
     * 다음 장외 동기화가 그 아래 끝과 겹칠 때까지 넘겨 메운다.
     */
    private final Map<String, Instant> gaps = new ConcurrentHashMap<>();
    private volatile boolean csvImported;
    /** 장 마감 뒤({@link #CLOSE_SETTLED}) 동기화를 실패 없이 마친 날(KST). */
    private volatile LocalDate settledDay;

    public MinuteBarArchiver(MarketDataPort marketData, MinuteBarStore store, MarketSessionService session,
                             @Value("${autostock.minute-archive.enabled:false}") boolean enabled,
                             @Value("${autostock.minute-archive.symbols:005930,000660,035420,035720,069500}") String symbolsCsv,
                             @Value("${autostock.minute-archive.dir:../data/minutes}") String csvDir, Clock clock) {
        this.marketData = marketData;
        this.store = store;
        this.session = session;
        this.enabled = enabled;
        this.symbols = List.of(symbolsCsv.split(",")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.csvDir = Paths.get(csvDir);
        this.clock = clock;
    }

    /** 평일 15:45 KST — 장 마감(15:30) 뒤, 일일 리포트(15:50) 전. */
    @Scheduled(cron = "0 45 15 * * MON-FRI", zone = "Asia/Seoul")
    public void archiveToday() {
        sync("정기 적재");
    }

    /** 기동 직후 한 번 — 꺼져 있던 동안의 빈 날과 첫 되채우기. 되채우기는 수 분 걸려 가상 스레드에서 돈다. */
    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        if (!enabled) {
            return;
        }
        Thread.ofVirtual().name("minute-bar-catch-up").start(() -> sync("기동"));
    }

    /** 매시 15분 — 장외인데 되채우기가 남았거나, 15:35 뒤인데 그날 동기화를 마치지 못했으면 다시 한다. */
    @Scheduled(cron = "0 15 * * * *", zone = "Asia/Seoul")
    public void hourlyCheck() {
        if (!enabled) {
            return;
        }
        ZonedDateTime now = nowKst();
        boolean backfillPending = !session.isActive() && (!backfillDone.containsAll(symbols) || !gaps.isEmpty());
        boolean unsettled = !now.toLocalTime().isBefore(CLOSE_SETTLED) && !now.toLocalDate().equals(settledDay);
        if (backfillPending || unsettled) {
            sync("시간별 점검");
        }
    }

    /** 동기화 한 번 — 클래스 설명 참고. 이미 돌고 있으면 건너뛴다. */
    void sync(String reason) {
        if (!enabled) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("분봉 적재({}) 건너뜀 — 앞선 동기화가 아직 도는 중", reason);
            return;
        }
        try {
            ZonedDateTime now = nowKst();
            importCsvOnce();
            boolean offHours = !session.isActive();
            int added = 0;
            int invalid = 0;
            int failed = 0;
            Instant from = null;
            Instant to = null;
            for (String symbol : symbols) {
                try {
                    SymbolResult r = syncSymbol(symbol, offHours, now);
                    added += r.added();
                    invalid += r.invalid();
                    if (r.refreshNeeded()) {
                        from = from == null || r.oldest().isBefore(from) ? r.oldest() : from;
                        to = to == null || r.newest().isAfter(to) ? r.newest() : to;
                    }
                    log.info(r.toLogLine());
                } catch (Exception e) {
                    // 한 종목 실패가 나머지를 막지 않는다. 남은 빈 구간은 syncSymbol이 기록해 다음 장외에 메운다
                    failed++;
                    log.warn("분봉 적재 실패({}): {} — {} (다음 장외 동기화가 이어서 메운다)", reason, StockNames.label(symbol), e.getMessage());
                }
            }
            String refreshed = refreshDailyBars(from, to);
            if (failed == 0 && !now.toLocalTime().isBefore(CLOSE_SETTLED)) {
                settledDay = now.toLocalDate();
            }
            log.info("분봉 적재 완료({}) — {}종목 중 실패 {}, 추가 {}행, 검증 제외 {}행, 일봉 집계 {}",
                    reason, symbols.size(), failed, added, invalid, refreshed);
        } finally {
            running.set(false);
        }
    }

    SymbolResult syncSymbol(String symbol, boolean offHours, ZonedDateTime now) {
        Optional<Instant> latest = store.latestBarTime(symbol);
        boolean needBackfill = false;
        if (!backfillDone.contains(symbol)) {
            Optional<Instant> earliest = store.earliestBarTime(symbol);
            if (earliest.isPresent() && earliest.get().isBefore(now.toInstant().minus(BACKFILL_COMPLETE_AGE))) {
                backfillDone.add(symbol);
            } else {
                needBackfill = true;
            }
        }
        boolean backfill = needBackfill && offHours;   // 장중이면 다음 장외(매시 점검)로 미룬다
        Instant gapFloor = gaps.get(symbol);
        boolean fillingGap = gapFloor != null && offHours && !backfill;
        // 멈출 곳: 되채우기면 브로커가 주는 끝, 빈 구간을 메우는 중이면 그 아래 끝, 아니면 DB의 최신 분봉
        Instant stopAt = backfill ? null : (fillingGap ? gapFloor : latest.orElse(null));
        // 장외는 멈출 곳까지 간다. 장중은 DB가 비었으면 오늘 페이지만, 있으면 약 1개월까지
        int maxPages = offHours ? BACKFILL_MAX_PAGES : (latest.isPresent() ? CATCH_UP_MAX_PAGES : 1);
        LocalDateTime nowLocal = now.toLocalDateTime();

        String nextKey = null;
        int pages = 0;
        int added = 0;
        int invalid = 0;
        Instant oldest = null;
        Instant newest = null;
        boolean reachedEnd = false;
        boolean overlapped = false;
        try {
            while (pages < maxPages) {
                MarketDataPort.MinuteBarPage page = marketData.minuteBarPage(symbol, nextKey);
                pages++;
                List<MinuteBarStore.Row> rows = new ArrayList<>(page.bars().size());
                Instant pageOldest = null;
                for (MarketDataPort.MinuteBar bar : page.bars()) {
                    Instant time = bar.time().atZone(MarketConstants.KST).toInstant();
                    pageOldest = pageOldest == null || time.isBefore(pageOldest) ? time : pageOldest;
                    if (bar.time().plusMinutes(1).isAfter(nowLocal)) {
                        continue;   // 아직 끝나지 않은 분 — 다음 동기화에서 넣는다
                    }
                    MinuteBarStore.Row row = toRow(bar);
                    if (row == null) {
                        invalid++;
                        continue;
                    }
                    rows.add(row);
                }
                int inserted = store.insert(symbol, rows);
                added += inserted;
                if (inserted > 0) {
                    for (MinuteBarStore.Row row : rows) {
                        oldest = oldest == null || row.barTime().isBefore(oldest) ? row.barTime() : oldest;
                        newest = newest == null || row.barTime().isAfter(newest) ? row.barTime() : newest;
                    }
                }
                if (page.bars().isEmpty() || !page.hasNext()) {
                    reachedEnd = true;
                    break;
                }
                if (stopAt != null && pageOldest != null && !pageOldest.isAfter(stopAt)) {
                    overlapped = true;   // 멈출 곳까지 내려왔다
                    break;
                }
                nextKey = page.nextKey();
            }
        } catch (RuntimeException e) {
            // 중간에 끊겼다 — 넣은 데까지는 남는다. 그 아래가 비었을 수 있어 빈 구간으로 기록한다(되채우기 중이면 다음에 처음부터)
            if (!backfill && stopAt != null) {
                gaps.merge(symbol, stopAt, (a, b) -> a.isBefore(b) ? a : b);
            }
            throw e;
        }

        if (backfill && reachedEnd) {
            backfillDone.add(symbol);
            gaps.remove(symbol);
            // 앞선 시도가 중간에 끊겨 집계되지 않은 구간까지 맞추려고, 되채우기를 끝내면 그 종목 전 구간을 다시 집계한다
            oldest = store.earliestBarTime(symbol).orElse(oldest);
            newest = store.latestBarTime(symbol).orElse(newest);
        } else if (fillingGap && (overlapped || reachedEnd)) {
            gaps.remove(symbol);
            // 빈 구간을 다 메웠다 — 앞선 시도에서 넣고 집계하지 못한 행까지 포함해 그 구간을 다시 집계한다
            oldest = oldest == null || gapFloor.isBefore(oldest) ? gapFloor : oldest;
            newest = store.latestBarTime(symbol).orElse(newest);
        }
        boolean gapLeft = !reachedEnd && !overlapped && stopAt != null;   // 페이지 상한에 걸려 멈췄다
        if (gapLeft) {
            gaps.merge(symbol, stopAt, (a, b) -> a.isBefore(b) ? a : b);   // 다음 장외에 이 아래 끝까지 메운다
        }
        boolean refreshNeeded = oldest != null && newest != null
                && (added > 0 || (backfill && reachedEnd) || (fillingGap && !gaps.containsKey(symbol)));
        return new SymbolResult(symbol, backfill, pages, added, invalid, oldest, newest, gaps.containsKey(symbol),
                refreshNeeded);
    }

    /**
     * 분봉 한 행을 저장 형식으로 바꾼다. V12의 CHECK와 같은 규칙으로 걸러 낸다 — 걸린 행은 null(검증 제외로 센다).
     * 가격은 원 단위 정수여야 하고, 거래량이 있는 분은 저가 ≤ 시가·종가 ≤ 고가여야 한다.
     */
    static MinuteBarStore.Row toRow(MarketDataPort.MinuteBar bar) {
        try {
            int open = won(bar.open());
            int high = won(bar.high());
            int low = won(bar.low());
            int close = won(bar.close());
            long volume = bar.volume();
            long accVolume = bar.accumulatedVolume();
            if (volume < 0 || accVolume < 0) {
                return null;
            }
            if (volume > 0 && (low <= 0 || low > Math.min(open, close) || high < Math.max(open, close))) {
                return null;
            }
            return new MinuteBarStore.Row(bar.time().atZone(MarketConstants.KST).toInstant(),
                    open, high, low, close, volume, accVolume);
        } catch (ArithmeticException | NullPointerException e) {
            return null;
        }
    }

    private static int won(BigDecimal price) {
        int value = price.intValueExact();   // 소수·범위 초과면 ArithmeticException
        if (value < 0) {
            throw new ArithmeticException("음수 가격");
        }
        return value;
    }

    /** 넣은 구간을 KST 하루 단위로 넓혀 일봉 집계를 다시 계산한다. @return 로그용 구간 설명 */
    private String refreshDailyBars(Instant from, Instant to) {
        if (from == null || to == null) {
            return "변경 없음";
        }
        LocalDate firstDay = from.atZone(MarketConstants.KST).toLocalDate();
        LocalDate lastDay = to.atZone(MarketConstants.KST).toLocalDate();
        try {
            store.refreshDailyBars(firstDay.atStartOfDay(MarketConstants.KST).toInstant(),
                    lastDay.plusDays(1).atStartOfDay(MarketConstants.KST).toInstant());
            return "갱신 " + firstDay.format(DAY) + "~" + lastDay.format(DAY);
        } catch (Exception e) {
            // 집계 정책(최근 10일, 매시)이 최근 구간은 다시 맞춘다. 과거 구간은 다음 동기화에서 다시 시도된다
            log.warn("일봉 집계 갱신 실패({}~{}): {}", firstDay, lastDay, e.getMessage());
            return "갱신 실패";
        }
    }

    /** 예전 CSV를 한 번 DB로 옮긴다(2026-09-11~10-02 적재분). 파일은 지우지 않는다 — 이관 확인 뒤 사용자가 정리한다. */
    private void importCsvOnce() {
        if (csvImported) {
            return;
        }
        csvImported = true;
        for (String symbol : symbols) {
            Path file = csvDir.resolve(symbol + ".csv");
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                List<MinuteBarStore.Row> rows = new ArrayList<>();
                int invalid = 0;
                for (int i = 1; i < lines.size(); i++) {   // 0번은 헤더 time,open,high,low,close,volume,acc_volume
                    MinuteBarStore.Row row = parseCsvLine(lines.get(i));
                    if (row == null) {
                        if (!lines.get(i).isBlank()) {
                            invalid++;
                        }
                        continue;
                    }
                    rows.add(row);
                }
                int added = 0;
                for (int from = 0; from < rows.size(); from += CSV_BATCH) {
                    added += store.insert(symbol, rows.subList(from, Math.min(rows.size(), from + CSV_BATCH)));
                }
                log.info("분봉 CSV 이관: {} {}행 중 {}행 추가(이미 있음 {}, 검증 제외 {}) — 파일은 그대로 둔다: {}",
                        StockNames.label(symbol), rows.size() + invalid, added, rows.size() - added, invalid, file);
            } catch (IOException | RuntimeException e) {
                log.warn("분봉 CSV 이관 실패: {} — {}", file, e.getMessage());
            }
        }
    }

    static MinuteBarStore.Row parseCsvLine(String line) {
        String[] f = line.trim().split(",");
        if (f.length != 7) {
            return null;
        }
        try {
            LocalDateTime time = LocalDateTime.parse(f[0], CSV_TIME);
            return toRow(new MarketDataPort.MinuteBar(time, new BigDecimal(f[1]), new BigDecimal(f[2]),
                    new BigDecimal(f[3]), new BigDecimal(f[4]), Long.parseLong(f[5]), Long.parseLong(f[6])));
        } catch (DateTimeParseException | NumberFormatException e) {
            return null;
        }
    }

    private ZonedDateTime nowKst() {
        return ZonedDateTime.now(clock.withZone(MarketConstants.KST));
    }

    /** 종목 한 개의 동기화 결과. */
    record SymbolResult(String symbol, boolean backfill, int pages, int added, int invalid,
                        Instant oldest, Instant newest, boolean gapLeft, boolean refreshNeeded) {

        String toLogLine() {
            String range = oldest == null || newest == null ? "" : String.format(", %s~%s",
                    oldest.atZone(MarketConstants.KST).toLocalDate().format(DAY),
                    newest.atZone(MarketConstants.KST).toLocalDate().format(DAY));
            return String.format("분봉 %s: %s %,d행 추가(%d페이지%s%s%s)", backfill ? "되채우기" : "적재",
                    StockNames.label(symbol), added, pages, range,
                    invalid > 0 ? String.format(", 검증 제외 %d행", invalid) : "",
                    gapLeft ? ", 빈 구간 남음 — 다음 장외에 되채움" : "");
        }
    }
}

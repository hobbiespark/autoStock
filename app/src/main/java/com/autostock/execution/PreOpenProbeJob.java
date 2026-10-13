package com.autostock.execution;

import com.autostock.common.event.TrProbeCompleted;
import com.autostock.common.util.MarketConstants;
import com.autostock.common.util.SecretMasking;
import com.autostock.common.util.StockNames;
import com.autostock.kiwoom.KiwoomRestClient;
import com.autostock.kiwoom.TrId;
import com.autostock.market.MarketCalendarService;
import com.autostock.market.MarketDataPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 장전 키움 응답 실측 — 매 거래일 {@code from}~{@code to}(기본 07:30~08:40) 5분마다 조회 TR 몇 개를 불러 응답 코드와 걸린 시간을
 * 남긴다. 주문은 내지 않는다(aiDoc/auto-measurement.md).
 *
 * <p>왜: 2026-10-08 07:53 기동 직후 분봉(ka10080) 5종목이 {@code [1631]/[1632] 서비스를 처리하는 중에 오류}로 실패했다(9/23 07:53
 * 시세 ka10001도 같았다). 07:56에는 정상이었다. 키움 모의 서버가 장 시작 전 어느 시각까지 이런 오류를 내는지 공식 안내가 없어
 * 매일 재서 데이터로 정한다 — 기동·장전 작업을 그 뒤로 두기 위해서다.
 *
 * <p>한 틱에 재는 것:
 * <ul>
 *   <li>시세 ka10001(첫 종목) — 캐시를 거치지 않고 직접 부른다</li>
 *   <li>잔고 kt00018 — 계좌 TR도 같은 시각에 되는지(응답 본문은 남기지 않는다)</li>
 *   <li>분봉 ka10080(종목마다 첫 페이지) — 가장 최근 분봉의 시각, 그리고 하루 한 번 전 거래일 분봉의 개수·첫 시각·끝 시각·정규장
 *       (09:00~15:30) 밖 분봉 수. NXT 거래 종목의 시간외 분봉이 섞이는지 재기 위해서다(10/8 08:15 매시 점검이 10/6~10/7 분봉
 *       955행을 더 넣었는데 KODEX 200만 0행이었다)</li>
 * </ul>
 * 결과는 {@code {outputDir}/pre_open_{yyyyMMdd}.jsonl}에 한 줄씩 덧붙이고(재기동해도 이어진다), {@code to} 시각의 틱에서 하루 요약을
 * 로그 한 줄과 {@code _summary.json}으로 남긴다. 오류가 하나라도 있던 날만 {@link TrProbeCompleted}로 텔레그램 INFO를 보낸다.
 */
@Component
public class PreOpenProbeJob {

    private static final Logger log = LoggerFactory.getLogger(PreOpenProbeJob.class);

    /** 키움 오류 코드 — 메시지 안 {@code [1632]}·{@code [8005:…]} 꼴. api-id({@code [ka10080]})와 섞이지 않게 숫자 4자리만. */
    private static final Pattern CODE = Pattern.compile("\\[(\\d{4})[:\\]]");
    private static final LocalTime REGULAR_OPEN = LocalTime.of(9, 0);
    private static final LocalTime REGULAR_CLOSE = LocalTime.of(15, 30);
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final PreOpenProbeProperties properties;
    private final KiwoomRestClient client;
    private final MarketDataPort marketData;
    private final MarketCalendarService calendar;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    /** 오늘 전 거래일 분봉을 이미 잰 종목 — 하루 한 번만 잰다. */
    private final Set<String> previousDayMeasured = ConcurrentHashMap.newKeySet();
    private volatile LocalDate measuredDay;
    private volatile LocalDate summarizedDay;

    public PreOpenProbeJob(PreOpenProbeProperties properties, KiwoomRestClient client, MarketDataPort marketData,
                           MarketCalendarService calendar, ObjectMapper objectMapper, ApplicationEventPublisher publisher,
                           Clock clock) {
        this.properties = properties;
        this.client = client;
        this.marketData = marketData;
        this.calendar = calendar;
        this.objectMapper = objectMapper;
        this.publisher = publisher;
        this.clock = clock;
    }

    /** 평일 7~8시 5분마다 — 거래일이고 창 안이면 한 번 잰다. */
    @Scheduled(cron = "0 */5 7-8 * * MON-FRI", zone = "Asia/Seoul")
    public void tick() {
        if (!properties.enabled() || properties.symbols() == null || properties.symbols().isEmpty()) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(MarketConstants.KST));
        LocalDate today = now.toLocalDate();
        // 예약 작업은 PC 시계 보정으로 몇 초 일찍 돌 수 있다(10/7 실측) — 가까운 분으로 본다
        LocalTime slot = now.plusSeconds(30).toLocalTime().truncatedTo(ChronoUnit.MINUTES);
        if (slot.isBefore(properties.from()) || slot.isAfter(properties.to()) || !calendar.isTradingDay(today)) {
            return;
        }
        if (!today.equals(measuredDay)) {
            measuredDay = today;
            previousDayMeasured.clear();
        }
        List<Map<String, Object>> samples = new ArrayList<>();
        String first = properties.symbols().get(0);
        samples.add(sample(slot, "ka10001", first,
                () -> client.call(TrId.STOCK_PRICE, "/api/dostk/stkinfo", Map.of("stk_cd", first))));
        samples.add(sample(slot, "kt00018", null,
                () -> client.call(TrId.ACCOUNT_BALANCE, "/api/dostk/acnt", Map.of("qry_tp", "1", "dmst_stex_tp", "KRX"))));
        for (String symbol : properties.symbols()) {
            samples.add(minuteSample(slot, today, symbol));
        }
        Path file = dir().resolve("pre_open_%s.jsonl".formatted(today.format(DateTimeFormatter.BASIC_ISO_DATE)));
        append(file, samples);
        if (!slot.isBefore(properties.to()) && !today.equals(summarizedDay)) {
            summarizedDay = today;
            summarize(today, file);
        }
    }

    private Path dir() {
        return Path.of(properties.outputDir());
    }

    /** TR 한 번 — 성공 여부, 실패면 오류 코드·메시지(가림), 걸린 시간. */
    private Map<String, Object> sample(LocalTime slot, String tr, String symbol, Supplier<?> call) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("slot", slot.toString());
        sample.put("tr", tr);
        if (symbol != null) {
            sample.put("symbol", symbol);
        }
        long started = clock.millis();
        try {
            call.get();
            sample.put("ok", true);
        } catch (RuntimeException e) {
            fail(sample, e);
        }
        sample.put("ms", clock.millis() - started);
        return sample;
    }

    /** 분봉 첫 페이지 — 가장 최근 분봉 시각, 하루 한 번 전 거래일 분봉의 개수·시각 범위·정규장 밖 수. */
    private Map<String, Object> minuteSample(LocalTime slot, LocalDate today, String symbol) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("slot", slot.toString());
        sample.put("tr", "ka10080");
        sample.put("symbol", symbol);
        long started = clock.millis();
        try {
            List<MarketDataPort.MinuteBar> bars = marketData.minuteBarPage(symbol, null).bars();
            sample.put("ok", true);
            sample.put("bars", bars.size());
            bars.stream().map(MarketDataPort.MinuteBar::time).max(LocalDateTime::compareTo)
                    .ifPresent(latest -> sample.put("latest", latest.format(MINUTE)));
            LocalDate previous = previousTradingDay(today);
            if (previous != null && previousDayMeasured.add(symbol)) {
                sample.put("previousDay", previousDayStats(bars, previous));
            }
        } catch (RuntimeException e) {
            fail(sample, e);
        }
        sample.put("ms", clock.millis() - started);
        return sample;
    }

    private static void fail(Map<String, Object> sample, RuntimeException e) {
        String message = SecretMasking.mask(String.valueOf(e.getMessage()));
        sample.put("ok", false);
        sample.put("code", errorCode(message));
        sample.put("error", message.length() > 160 ? message.substring(0, 160) + "…" : message);
    }

    /** 메시지 안 키움 오류 코드(숫자 4자리) — 없으면 예외 종류를 알 수 없는 "?"(네트워크 등). */
    static String errorCode(String message) {
        Matcher matcher = CODE.matcher(message == null ? "" : message);
        return matcher.find() ? matcher.group(1) : "?";
    }

    /** 전 거래일 — 10일 안에서 찾는다(연휴). */
    private LocalDate previousTradingDay(LocalDate today) {
        LocalDate day = today.minusDays(1);
        for (int i = 0; i < 10; i++, day = day.minusDays(1)) {
            if (calendar.isTradingDay(day)) {
                return day;
            }
        }
        return null;
    }

    /** 그날 분봉의 개수·첫 시각·끝 시각·정규장(09:00~15:30) 밖 분봉 수 — 첫 페이지 안에 있는 만큼(약 900개). */
    static Map<String, Object> previousDayStats(List<MarketDataPort.MinuteBar> bars, LocalDate day) {
        List<LocalTime> times = bars.stream()
                .map(MarketDataPort.MinuteBar::time)
                .filter(time -> time.toLocalDate().equals(day))
                .map(LocalDateTime::toLocalTime)
                .sorted()
                .toList();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("date", day.toString());
        stats.put("bars", times.size());
        if (!times.isEmpty()) {
            stats.put("first", times.get(0).toString());
            stats.put("last", times.get(times.size() - 1).toString());
        }
        stats.put("outsideRegular", times.stream()
                .filter(time -> time.isBefore(REGULAR_OPEN) || time.isAfter(REGULAR_CLOSE))
                .count());
        return stats;
    }

    /** 한 틱의 기록을 그날 파일에 한 줄씩 덧붙인다 — 실패해도 다음 틱은 돈다. */
    private void append(Path file, List<Map<String, Object>> samples) {
        try {
            Files.createDirectories(file.getParent());
            StringBuilder lines = new StringBuilder();
            for (Map<String, Object> sample : samples) {
                lines.append(objectMapper.writeValueAsString(sample)).append('\n');
            }
            Files.writeString(file, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            log.warn("장전 키움 응답 실측 기록 실패: {} — {}", file.toAbsolutePath(), e.getMessage());
        }
    }

    /** 그날 파일 전체로 요약 — 로그 한 줄, 요약 파일, 오류가 있었으면 텔레그램 INFO. */
    @SuppressWarnings("unchecked")
    void summarize(LocalDate today, Path file) {
        List<Map<String, Object>> samples = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    samples.add(objectMapper.readValue(line, Map.class));
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("장전 키움 응답 실측 요약 실패 — 기록을 읽지 못했다: {}", e.getMessage());
            return;
        }
        // TR·코드별 오류 — 첫 시각·끝 시각·횟수
        Map<String, List<String>> errors = new TreeMap<>();
        for (Map<String, Object> sample : samples) {
            if (Boolean.FALSE.equals(sample.get("ok"))) {
                String key = "%s %s".formatted(sample.get("tr"), sample.get("code"));
                errors.computeIfAbsent(key, k -> new ArrayList<>()).add(String.valueOf(sample.get("slot")));
            }
        }
        List<String> errorParts = new ArrayList<>();
        errors.forEach((key, slots) -> errorParts.add("%s %s~%s %d회".formatted(key, slots.get(0), slots.get(slots.size() - 1),
                slots.size())));
        List<String> barParts = new ArrayList<>();
        for (Map<String, Object> sample : samples) {
            if (sample.get("previousDay") instanceof Map<?, ?> stats) {
                barParts.add("%s %s개 %s~%s(정규장 밖 %s)".formatted(StockNames.label(String.valueOf(sample.get("symbol"))),
                        stats.get("bars"), stats.get("first"), stats.get("last"), stats.get("outsideRegular")));
            }
        }
        long errorCount = errors.values().stream().mapToLong(List::size).sum();
        String line = "장전 키움 응답 실측 %s~%s — %d회 중 오류 %d회%s · 전 거래일 분봉 %s".formatted(
                properties.from(), properties.to(), samples.size(), errorCount,
                errorParts.isEmpty() ? "" : "(" + String.join(", ", errorParts) + ")",
                barParts.isEmpty() ? "기록 없음" : String.join(", ", barParts));
        log.info(line);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("date", today.toString());
        summary.put("samples", samples.size());
        summary.put("errors", errors);
        summary.put("line", line);
        try {
            Files.write(dir().resolve("pre_open_%s_summary.json".formatted(today.format(DateTimeFormatter.BASIC_ISO_DATE))),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(summary));
        } catch (IOException e) {
            log.warn("장전 키움 응답 실측 요약 저장 실패 — {}", e.getMessage());
        }
        if (errorCount > 0) {
            publisher.publishEvent(new TrProbeCompleted(today, true, line, clock.instant()));
        }
    }
}

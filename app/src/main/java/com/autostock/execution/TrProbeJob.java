package com.autostock.execution;

import com.autostock.common.event.ProbeOrderPlaced;
import com.autostock.common.event.TrProbeCompleted;
import com.autostock.common.util.KiwoomNumbers;
import com.autostock.common.util.MarketConstants;
import com.autostock.common.util.SecretMasking;
import com.autostock.common.util.StockNames;
import com.autostock.kiwoom.KiwoomProperties;
import com.autostock.kiwoom.KiwoomRestClient;
import com.autostock.kiwoom.TrId;
import com.autostock.market.MarketCalendarService;
import com.autostock.risk.KillSwitch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 실행 계획 1.6 — 주문 경로 실측. 처음엔 하루짜리 실측이었다(1차 10/7 지정가 매수·취소, 2차 10/8 매도·체결 왕복 추가).
 * 2026-10-08 사용자 요청("실측 필요한 내용 전부 코드로, 매일 xx시")으로 <b>매 거래일 점검</b>이 됐다 — 같은 주문 경로를 매일
 * 한 번 돌려, 키움 응답의 형식과 동작이 앱이 기대하는 그대로인지 확인한다. 모의 서버에서만 돈다(aiDoc/auto-measurement.md).
 *
 * <p>순서:
 * <ol>
 *   <li>지정가 매수 1주 — 현재가보다 {@code discountPct}% 낮게(체결되지 않게) → 미체결(ka10075)·주문체결내역(kt00007) 조회 →
 *       취소(kt10003) → 주문체결내역(주문순·미체결만)·미체결·체결(ka10076) 조회</li>
 *   <li>지정가 매도 1주 — 현재가보다 {@code discountPct}% 높게(보유분, 체결되지 않게) → 미체결·주문체결내역 → 취소 → 주문체결내역.
 *       <b>보유가 없으면</b>(2026-10-15 실측: 10/13 국면 OFF 청산 뒤 키움 {@code 800033 매도가능수량이 부족합니다}) 실측 실패로 세지
 *       않고 건너뛴다. 체결 왕복 날에는 시장가로 산 1주를 들고 있는 동안 지정가 매도·취소를 잰 뒤 판다</li>
 *   <li>체결 왕복({@code fillRoundTripDays}의 요일, 그날 빠졌으면 같은 주 다음 거래일) — 시장가 매수 1주 → 체결 확인
 *       (주문체결내역·체결 조회) → 시장가 매도 1주 → 조회.
 *       매수가 끝내 체결되지 않으면 취소하고 매도하지 않는다(원래 보유를 줄이지 않는다)</li>
 * </ol>
 * 단계마다 기대를 점검한다 — 형식(앱이 읽는 필드가 있는가)과 동작(미체결에 보이나, 매수·매도 표기가 맞나, 취소 뒤 빠지나, 잔량이
 * 맞나). 하나라도 어긋나거나 사람이 확인할 일(취소·매도 실패 등)이 생기면 {@link TrProbeCompleted}(실패)를 발행한다 — monitor가
 * 텔레그램 WARN으로 보낸다. 모두 맞으면 로그 한 줄만 남긴다({@code notifySuccess}면 텔레그램 INFO도).
 *
 * <p>응답 원문은 계좌번호를 가린 뒤 {@code {outputDir}/{yyyyMMdd}/tr_probe_{yyyyMMdd}_{순번}_{TR}_{조건}.json}으로 남기고, 요약
 * ({@code _00_summary.json})에 우리 주문(원주문·취소 주문 포함)이 어느 조회에 어떤 모양으로 나왔는지와 점검 결과를 적는다.
 *
 * <p>안전장치:
 * <ul>
 *   <li>{@code execution.tr-probe.enabled=true}이고 {@code from}~{@code until} 사이 거래일에만, {@code at}~14:50 사이 첫 분에 하루 한 번</li>
 *   <li>키움 REST 주소가 모의 서버({@value #MOCK_HOST})가 아니면 절대 돌지 않는다</li>
 *   <li>킬스위치가 켜져 있으면 미룬다(다음 분에 다시 본다)</li>
 *   <li>현재가를 못 읽어 주문을 하나도 내지 못했으면 요약을 남기지 않고 {@value #START_ATTEMPTS}번까지 5분 간격으로 다시 시도한다
 *       (2026-10-12 대기 복귀 직후 망 없음 실측). 마지막 시도나 14:50이 가까우면 실패로 요약을 남긴다</li>
 *   <li>그날 요약 파일이 이미 있으면 다시 돌지 않는다 — 재기동해도 하루 한 번</li>
 *   <li>첫 지정가 매수가 접수되지 않으면(장 종료·계좌 문제 등) 나머지 주문을 내지 않는다</li>
 * </ul>
 *
 * <p>이 주문들은 앱의 주문 장부(trading)를 거치지 않는다. 주문 한도·일련번호를 쓰지 않고, 일별 성과의 주문 수에도 안 잡힌다.
 * 주문번호를 받을 때마다 {@link ProbeOrderPlaced}를 발행해, trading의 체결통보 처리가 실측 주문으로 알아보고 반영하지 않는다
 * (2026-10-15 — 예전에는 "수동 주문 등으로 추정" WARN이 실측 날마다 남았다). 체결 왕복은 1주를 사서 1주를 파므로 보유 수량은 그대로다.
 */
@Component
public class TrProbeJob {

    private static final Logger log = LoggerFactory.getLogger(TrProbeJob.class);

    static final String MOCK_HOST = "mockapi.kiwoom.com";
    /** 이 시각 뒤로는 시작하지 않는다 — 장 마감 동시호가(15:20~) 전에 주문·취소·체결 왕복을 끝낸다. */
    static final LocalTime DEADLINE = LocalTime.of(14, 50);
    /** 시장가 매수가 체결됐는지 주문체결내역으로 보는 횟수(첫 조회 포함, {@link #SETTLE} 간격). */
    static final int FILL_CHECKS = 3;
    /**
     * 현재가를 못 읽어 주문을 하나도 내지 못했을 때 그날 시도하는 횟수(첫 시도 포함) — {@link #START_RETRY_INTERVAL} 간격.
     * 2026-10-12: PC가 대기에서 잠깐 깨어난 순간(망 없음) 첫 단계에서 실패해 그날 실측(체결 왕복 포함)이 통째로 빠졌다.
     */
    static final int START_ATTEMPTS = 4;
    static final Duration START_RETRY_INTERVAL = Duration.ofMinutes(5);
    /** 앱(1.6)이 미체결(ka10075) 행에서 읽을 필드 — 없어지면 형식이 바뀐 것이다(10/7 실측). */
    static final List<String> OUTSTANDING_FIELDS =
            List.of("ord_no", "stk_cd", "io_tp_nm", "ord_qty", "oso_qty", "cntr_qty", "orig_ord_no");
    /** 앱(1.6)이 주문체결내역(kt00007) 행에서 읽을 필드(10/7 실측). */
    static final List<String> DETAIL_FIELDS =
            List.of("ord_no", "ori_ord", "stk_cd", "io_tp_nm", "ord_qty", "cntr_qty", "cntr_uv", "ord_remnq", "mdfy_cncl");
    /** 체결(ka10076) 행에서 읽을 필드(스펙 — 체결 행은 체결 왕복에서 처음 관측한다). */
    static final List<String> EXECUTION_FIELDS = List.of("ord_no", "io_tp_nm", "cntr_qty", "cntr_pric");

    private static final String ORDER_PATH = "/api/dostk/ordr";
    private static final String ACCOUNT_PATH = "/api/dostk/acnt";
    private static final String STOCK_INFO_PATH = "/api/dostk/stkinfo";
    /** 주문 접수·취소·체결이 조회에 반영되기를 기다리는 시간. */
    private static final Duration SETTLE = Duration.ofSeconds(3);
    /** 응답 원문에서 값을 가리는 키(소문자 부분 일치) — 계좌번호·관리사원번호. 공개 리포에 남길 수 있기 때문이다. */
    private static final List<String> MASKED_KEY_PARTS = List.of("acnt", "empno");
    /** 행이 우리 주문(또는 그 취소 주문)인지 볼 때 비교하는 주문번호 키 — TR마다 이름이 다르다(10/7 실측). */
    private static final List<String> ORDER_NO_KEYS = List.of("ord_no", "ori_ord", "orig_ord_no", "base_orig_ord_no");
    /** 원주문 번호 키 — 취소·정정 주문 행에서 원래 주문을 가리킨다. */
    private static final List<String> ORIGINAL_NO_KEYS = List.of("ori_ord", "orig_ord_no", "base_orig_ord_no");
    /** 보유가 없어 매도가 거부된 키움 응답 표시(2026-10-15 실측: {@code [kt10001] [2000](800033:모의투자 매도가능수량이 부족합니다.)}). */
    private static final List<String> NO_HOLDING_MARKERS = List.of("800033", "매도가능수량");
    /** 보유가 없어 지정가 매도를 건너뛴 결과 — 실측 실패로 세지 않는다. */
    static final String NO_HOLDING_RESULT = "건너뜀 — 매도할 보유 없음";

    private final TrProbeProperties properties;
    private final KiwoomRestClient client;
    private final KiwoomProperties kiwoomProperties;
    private final KillSwitch killSwitch;
    private final MarketCalendarService calendar;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private final Sleeper sleeper;
    /** 오늘 이미 돌았거나 돌지 않기로 정한 날 — 같은 날 다시 보지 않는다. */
    private volatile LocalDate doneDay;
    /** 현재가를 못 읽어 시작하지 못한 날·횟수·다음 시도 시각 — 날이 바뀌면 처음부터 센다. */
    private volatile LocalDate attemptDay;
    private volatile int attempts;
    private volatile LocalTime nextAttemptAt;
    private volatile String lastDeferReason;

    @Autowired
    public TrProbeJob(TrProbeProperties properties, KiwoomRestClient client, KiwoomProperties kiwoomProperties,
                      KillSwitch killSwitch, MarketCalendarService calendar, ObjectMapper objectMapper,
                      ApplicationEventPublisher publisher, Clock clock) {
        this(properties, client, kiwoomProperties, killSwitch, calendar, objectMapper, publisher, clock, Thread::sleep);
    }

    /** 테스트용 — 대기를 실제로 잠들지 않게 바꿔 끼운다. */
    TrProbeJob(TrProbeProperties properties, KiwoomRestClient client, KiwoomProperties kiwoomProperties,
               KillSwitch killSwitch, MarketCalendarService calendar, ObjectMapper objectMapper,
               ApplicationEventPublisher publisher, Clock clock, Sleeper sleeper) {
        this.properties = properties;
        this.client = client;
        this.kiwoomProperties = kiwoomProperties;
        this.killSwitch = killSwitch;
        this.calendar = calendar;
        this.objectMapper = objectMapper;
        this.publisher = publisher;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** 평일 9~14시 매분 — 오늘 돌 날·시각이 됐는지 보고, 됐으면 하루 한 번 돈다. */
    @Scheduled(cron = "0 * 9-14 * * MON-FRI", zone = "Asia/Seoul")
    public void tick() {
        if (!properties.enabled() || properties.from() == null) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(MarketConstants.KST));
        LocalDate today = now.toLocalDate();
        LocalTime time = now.toLocalTime();
        if (today.equals(doneDay) || today.isBefore(properties.from())
                || (properties.until() != null && today.isAfter(properties.until()))
                || time.isBefore(properties.at()) || !time.isBefore(DEADLINE)) {
            return;
        }
        if (!calendar.isTradingDay(today)) {
            doneDay = today;
            return;
        }
        if (today.equals(attemptDay) && nextAttemptAt != null && time.isBefore(nextAttemptAt)) {
            return;   // 앞선 시도가 현재가를 못 읽었다 — 간격을 두고 다시 본다
        }
        Path dir = Path.of(properties.outputDir(), today.format(DateTimeFormatter.BASIC_ISO_DATE));
        Path summary = dir.resolve(fileName(today, 0, "summary"));
        if (Files.exists(summary)) {
            doneDay = today;
            log.info("TR 실측(1.6): 오늘 결과가 이미 있다 — 다시 돌지 않는다({})", summary.toAbsolutePath());
            return;
        }
        String host = URI.create(kiwoomProperties.restBaseUrl()).getHost();
        if (!MOCK_HOST.equalsIgnoreCase(host)) {
            doneDay = today;
            log.error("TR 실측(1.6) 거부 — 키움 주소가 모의 서버가 아니다({}). 실서버에서는 돌지 않는다", host);
            return;
        }
        int pct = properties.discountPct();
        if (pct < 5 || pct > 29) {
            doneDay = today;
            log.error("TR 실측(1.6) 거부 — discount-pct {}: 5~29만 허용(가격제한폭 ±30% 안, 체결되지 않을 만큼 멀게)", pct);
            return;
        }
        if (killSwitch.isEngaged()) {
            defer("킬스위치 작동 중");
            return;
        }
        if (!today.equals(attemptDay)) {
            attemptDay = today;
            attempts = 0;
            nextAttemptAt = null;
        }
        int attempt = attempts + 1;
        attempts = attempt;
        boolean lastAttempt = attempt >= START_ATTEMPTS || !time.plus(START_RETRY_INTERVAL).isBefore(DEADLINE);
        doneDay = today;
        if (!new Probe(today, dir, pct, fillRoundTripToday(today), attempt, lastAttempt).run()) {
            doneDay = null;
            nextAttemptAt = time.plus(START_RETRY_INTERVAL);
        }
    }

    /**
     * 오늘 체결 왕복을 할까 — 정한 요일이면 한다. 아니면 이번 주(월~어제)의 정한 요일 뒤로 체결 왕복을 실제로 한 날(시장가 매수가
     * 접수된 날)이 없을 때 오늘 한다. 2026-10-12(월)처럼 PC가 멈추거나 앱이 꺼져 그날 빠지면, 앱이 같은 주 다음 거래일에 스스로
     * 메운다(사람이 설정을 바꿨다 되돌리지 않게). 지난주 것은 보지 않는다.
     */
    boolean fillRoundTripToday(LocalDate today) {
        if (properties.fillRoundTripOn(today)) {
            return true;
        }
        LocalDate weekStart = today.with(DayOfWeek.MONDAY);
        LocalDate due = null;
        for (LocalDate day = today.minusDays(1); !day.isBefore(weekStart); day = day.minusDays(1)) {
            if (properties.fillRoundTripOn(day)) {
                due = day;
                break;
            }
        }
        if (due == null) {
            return false;
        }
        for (LocalDate day = due; day.isBefore(today); day = day.plusDays(1)) {
            if (roundTripDone(day)) {
                return false;
            }
        }
        log.info("TR 실측(1.6): 이번 주 체결 왕복({} {})이 아직 없다 — 오늘 한다", due, due.getDayOfWeek());
        return true;
    }

    /** 그날 요약에 체결 왕복의 시장가 매수 주문번호가 있으면(접수까지 갔으면) 한 것으로 본다. 요약이 없거나 못 읽으면 안 한 것. */
    private boolean roundTripDone(LocalDate day) {
        Path summary = Path.of(properties.outputDir(), day.format(DateTimeFormatter.BASIC_ISO_DATE))
                .resolve(fileName(day, 0, "summary"));
        if (!Files.exists(summary)) {
            return false;
        }
        try {
            JsonNode buyNo = objectMapper.readTree(summary.toFile()).path("scenarios").path("fill-round-trip").path("buyOrderNo");
            return buyNo.isTextual() && !buyNo.asText().isBlank();
        } catch (IOException | RuntimeException e) {
            log.warn("TR 실측(1.6): {} 요약을 읽지 못했다 — 체결 왕복을 안 한 날로 본다: {}", day, e.getMessage());
            return false;
        }
    }

    private void defer(String reason) {
        if (!reason.equals(lastDeferReason)) {
            lastDeferReason = reason;
            log.warn("TR 실측(1.6) 미룸 — {}. 다음 분에 다시 본다", reason);
        }
    }

    /** 현재가에서 pct% 내린 값을 호가 단위로 내림한 지정가 — 매수용, 체결되지 않을 가격. */
    static long limitPrice(BigDecimal currentPrice, int discountPct) {
        return floorToTick(currentPrice.multiply(BigDecimal.valueOf(100L - discountPct)));
    }

    /** 현재가에서 pct% 올린 값을 호가 단위로 내림한 지정가 — 매도용, 체결되지 않을 가격(상한가 +30% 안). */
    static long limitPriceUp(BigDecimal currentPrice, int premiumPct) {
        return floorToTick(currentPrice.multiply(BigDecimal.valueOf(100L + premiumPct)));
    }

    /** {@code hundredTimes}(가격×100)를 원 단위로 내리고 호가 단위로 내림한다. */
    private static long floorToTick(BigDecimal hundredTimes) {
        long raw = hundredTimes.divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR).longValueExact();
        long tick = tickSize(raw);
        return raw / tick * tick;
    }

    /**
     * 주식 호가 단위(KRX, 2023-01 개편). ETF·ETN은 5원 단위라 이 단위(모두 5의 배수 이상)로 내림해도 유효하다.
     */
    static long tickSize(long price) {
        if (price < 2_000) {
            return 1;
        }
        if (price < 5_000) {
            return 5;
        }
        if (price < 20_000) {
            return 10;
        }
        if (price < 50_000) {
            return 50;
        }
        if (price < 200_000) {
            return 100;
        }
        if (price < 500_000) {
            return 500;
        }
        return 1_000;
    }

    static String fileName(LocalDate day, int seq, String label) {
        return "tr_probe_%s_%02d_%s.json".formatted(day.format(DateTimeFormatter.BASIC_ISO_DATE), seq, label);
    }

    /**
     * 계좌번호·관리사원번호 값을 가린 사본(키 이름은 그대로 둔다 — 필드 확정이 목적이다). 글자·숫자 값만 가린다:
     * kt00007의 목록 키 {@code acnt_ord_cntr_prps_dtl}처럼 이름에 {@code acnt}가 들어간 목록·객체는 통째로 가리지 않고
     * 안으로 들어가 가린다(10/7 1차 실측에서 목록이 통째로 가려진 결함 P-1).
     */
    static Object mask(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                String key = String.valueOf(k);
                boolean container = v instanceof Map<?, ?> || v instanceof List<?>;
                boolean secret = !container && MASKED_KEY_PARTS.stream().anyMatch(key.toLowerCase(Locale.ROOT)::contains);
                copy.put(key, secret && v != null && !String.valueOf(v).isBlank() ? "****" : mask(v));
            });
            return copy;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(TrProbeJob::mask).toList();
        }
        return value;
    }

    /** 매도할 보유가 없어 거부된 주문인가 — 키움 {@code 800033 매도가능수량이 부족합니다}(2026-10-15 실측). */
    static boolean isNoHolding(String message) {
        return message != null && NO_HOLDING_MARKERS.stream().anyMatch(message::contains);
    }

    /** 주문번호 비교 — 응답마다 자릿수(앞자리 0)가 다를 수 있다. */
    static boolean sameOrderNo(Object a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String left = String.valueOf(a).trim().replaceFirst("^0+(?=.)", "");
        String right = b.trim().replaceFirst("^0+(?=.)", "");
        return !left.isEmpty() && left.equals(right);
    }

    /** 응답 목록(배열 값)의 행들 — 목록이 없으면 빈 목록. */
    static List<Map<?, ?>> rows(Map<String, Object> response) {
        List<Map<?, ?>> rows = new ArrayList<>();
        if (response != null) {
            for (Object value : response.values()) {
                if (value instanceof List<?> list) {
                    for (Object row : list) {
                        if (row instanceof Map<?, ?> map) {
                            rows.add(map);
                        }
                    }
                }
            }
        }
        return rows;
    }

    /** 응답 목록에서 그 주문번호({@code ord_no}) 행 — 없으면 null. */
    static Map<?, ?> rowOf(Map<String, Object> response, String orderNo) {
        for (Map<?, ?> row : rows(response)) {
            if (sameOrderNo(row.get("ord_no"), orderNo)) {
                return row;
            }
        }
        return null;
    }

    /** 응답 목록에서 원주문이 그 번호인 행(취소·정정 주문 행) — 없으면 null. */
    static Map<?, ?> childRowOf(Map<String, Object> response, String originalNo) {
        for (Map<?, ?> row : rows(response)) {
            for (String key : ORIGINAL_NO_KEYS) {
                if (sameOrderNo(row.get(key), originalNo)) {
                    return row;
                }
            }
        }
        return null;
    }

    /** 행에 없는 필드 — 형식 변화 점검용. */
    static List<String> missing(Map<?, ?> row, List<String> fields) {
        return fields.stream().filter(field -> !row.containsKey(field)).toList();
    }

    /**
     * 응답 목록에서 그 주문번호 행의 체결 수량({@code cntr_qty}) 중 가장 큰 값. kt00007(0으로 채운 10자리)·ka10076 모두
     * 같은 키다. 행이 없거나 응답이 없으면 0.
     */
    static long filledQuantity(Map<String, Object> response, String orderNo) {
        long max = 0;
        for (Map<?, ?> row : rows(response)) {
            if (sameOrderNo(row.get("ord_no"), orderNo)) {
                max = Math.max(max, number(row, "cntr_qty"));
            }
        }
        return max;
    }

    private static long number(Map<?, ?> row, String key) {
        return KiwoomNumbers.toLongOrZero(row.get(key));
    }

    private static String text(Map<?, ?> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** 한 번의 실측 실행 — 순번·결과·점검을 모은다. */
    private final class Probe {
        private final LocalDate day;
        private final Path dir;
        private final int pct;
        private final boolean fillRoundTrip;
        private final String symbol = properties.symbol();
        private final List<Map<String, Object>> steps = new ArrayList<>();
        /** 이번 실측이 낸 주문의 번호(원주문·취소 주문·시장가 주문) — 조회 결과에서 우리 행을 고르는 기준. */
        private final Set<String> ourOrderNos = new LinkedHashSet<>();
        private final Map<String, Object> scenarios = new LinkedHashMap<>();
        /** 기대 점검 — 이름·결과·어긋난 내용. */
        private final List<Map<String, Object>> checks = new ArrayList<>();
        /** 사람이 확인해야 할 일(접수 거부·취소 실패·매도 실패·점검 실패) — 알림에 그대로 적는다. */
        private final List<String> problems = new ArrayList<>();
        private final int attempt;
        private final boolean lastAttempt;
        private int seq;
        private int failures;
        private int ourOrderSeen;
        /** 현재가를 못 읽어 주문 없이 멈췄고 그날 다시 시도한다 — 요약·알림을 남기지 않는다. */
        private boolean retryLater;
        /** 이번 실측의 현재가 — 체결 왕복 중 지정가 매도를 다시 낼 때 쓴다. */
        private BigDecimal currentPrice;
        /** 방금 낸 주문이 보유가 없어 거부됐다(800033) — {@link #step}이 적고 {@link #limitScenario}가 읽는다. */
        private boolean noHoldingRejected;
        /** 지정가 매도를 보유가 없어 건너뛰었다 — 체결 왕복 날이면 산 1주로 다시 잰다. */
        private boolean limitSellSkipped;
        /** 체결 왕복에서 산 1주를 들고 지정가 매도를 재는 중 — 이때 거부는 진짜 실패다. */
        private boolean holdingProbeShare;

        Probe(LocalDate day, Path dir, int pct, boolean fillRoundTrip, int attempt, boolean lastAttempt) {
            this.day = day;
            this.dir = dir;
            this.pct = pct;
            this.fillRoundTrip = fillRoundTrip;
            this.attempt = attempt;
            this.lastAttempt = lastAttempt;
        }

        /** 한 번 돈다 — 요약을 남겼으면 true, 현재가를 못 읽어 그날 다시 시도해야 하면 false. */
        boolean run() {
            String summaryLine;
            boolean success;
            try {
                Files.createDirectories(dir);
                log.info("TR 실측(1.6) 시작{} — {} 1주 지정가 매수(-{}%)·매도(+{}%) → 조회 → 취소 → 조회{}. 결과 폴더 {}",
                        attempt > 1 ? "(%d번째 시도)".formatted(attempt) : "", StockNames.label(symbol), pct, pct,
                        fillRoundTrip ? ", 체결 왕복(시장가 매수 → 체결 확인 → 시장가 매도)" : "", dir.toAbsolutePath());
                summaryLine = probe();
                if (retryLater) {
                    log.warn("TR 실측(1.6) 시작 못 함 — 현재가를 읽지 못했다({}/{}번째 시도, 주문 없음). {}분 뒤 다시 본다",
                            attempt, START_ATTEMPTS, START_RETRY_INTERVAL.toMinutes());
                    return false;
                }
                success = failures == 0 && problems.isEmpty();
            } catch (RuntimeException | IOException e) {
                success = false;
                summaryLine = "TR 실측(1.6) 중단 — %s: %s".formatted(e.getClass().getSimpleName(),
                        SecretMasking.mask(String.valueOf(e.getMessage())));
                log.error("TR 실측(1.6) 중단", SecretMasking.sanitizeForLogging(e));
            }
            if (success) {
                log.info(summaryLine);
            } else {
                log.warn(summaryLine);
            }
            if (!success || properties.notifySuccess()) {
                publisher.publishEvent(new TrProbeCompleted(day, success, summaryLine, clock.instant()));
            }
            return true;
        }

        private String probe() {
            Map<String, Object> quote = step(TrId.STOCK_PRICE, STOCK_INFO_PATH, Map.of("stk_cd", symbol), "quote");
            BigDecimal current = quote == null ? BigDecimal.ZERO : KiwoomNumbers.toBigDecimal(quote.get("cur_prc")).abs();
            if (current.signum() <= 0) {
                if (!lastAttempt) {
                    retryLater = true;   // 주문 전이라 다시 해도 안전하다 — 망이 늦게 붙는 대기 복귀 직후 등
                    return null;
                }
                problems.add("현재가를 읽지 못해 주문하지 않았다(%d번 시도)".formatted(attempt));
                return finish(null);
            }
            currentPrice = current;
            if (!limitScenario(true, limitPrice(current, pct))) {
                // 첫 주문부터 거부되면(장 종료·계좌 문제 등) 나머지 주문도 같은 이유로 거부된다 — 더 내지 않는다
                problems.add("지정가 매수가 접수되지 않아 나머지 주문을 내지 않았다");
                return finish(current);
            }
            limitScenario(false, limitPriceUp(current, pct));
            if (fillRoundTrip) {
                fillRoundTrip();
            }
            return finish(current);
        }

        /**
         * 지정가 1주 → 미체결로 어떻게 보이나 → 취소 → 취소가 주문내역에 어떻게 남나. 단계마다 기대를 점검한다.
         *
         * @return 주문이 접수됐는가
         */
        private boolean limitScenario(boolean buy, long price) {
            String side = buy ? "buy" : "sell";
            String name = buy ? "지정가 매수" : "지정가 매도";
            String sideWord = buy ? "매수" : "매도";
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("price", price);
            if (holdingProbeShare) {
                result.put("note", "보유가 없어 첫 지정가 매도는 거부(800033) — 체결 왕복에서 산 1주로 잼");
            }
            scenarios.put("limit-" + side, result);
            noHoldingRejected = false;
            String orderNo = order(buy ? TrId.ORDER_BUY : TrId.ORDER_SELL, String.valueOf(price), "0", side + "-limit",
                    !buy && !holdingProbeShare);
            result.put("orderNo", orderNo);
            if (orderNo == null) {
                if (noHoldingRejected) {
                    result.put("result", NO_HOLDING_RESULT);
                    limitSellSkipped = true;
                } else {
                    result.put("result", "접수 안 됨");
                }
                return false;
            }
            pause();
            Map<String, Object> open = step(TrId.OUTSTANDING_ORDERS, ACCOUNT_PATH, outstanding(), side + "-open");
            if (open != null) {   // 조회 자체가 실패하면 실패 수로 이미 센다 — 점검은 응답이 있을 때만
                Map<?, ?> row = rowOf(open, orderNo);
                expect(name + " — 미체결에 보임", row != null, "ka10075에 주문 " + orderNo + " 없음");
                if (row != null) {
                    List<String> miss = missing(row, OUTSTANDING_FIELDS);
                    expect("미체결 행 필드", miss.isEmpty(), "없는 필드 " + miss);
                    expect(name + " — 미체결 표기·잔량", text(row, "io_tp_nm").contains(sideWord) && number(row, "oso_qty") == 1,
                            "io_tp_nm=%s, oso_qty=%s".formatted(text(row, "io_tp_nm"), text(row, "oso_qty")));
                }
            }
            Map<String, Object> detail = step(TrId.ORDER_EXECUTION_DETAIL, ACCOUNT_PATH, orderDetail("1"), side + "-open");
            if (detail != null) {
                Map<?, ?> row = rowOf(detail, orderNo);
                expect(name + " — 주문내역에 보임", row != null, "kt00007에 주문 " + orderNo + " 없음");
                if (row != null) {
                    List<String> miss = missing(row, DETAIL_FIELDS);
                    expect("주문내역 행 필드", miss.isEmpty(), "없는 필드 " + miss);
                    expect(name + " — 주문내역 표기·잔량",
                            text(row, "io_tp_nm").contains(sideWord) && number(row, "ord_remnq") == 1 && number(row, "cntr_qty") == 0,
                            "io_tp_nm=%s, ord_remnq=%s, cntr_qty=%s".formatted(text(row, "io_tp_nm"), text(row, "ord_remnq"),
                                    text(row, "cntr_qty")));
                }
            }
            Map<String, Object> cancel = cancel(orderNo, side);
            result.put("cancelOrderNo", cancel == null ? null : cancel.get("ord_no"));
            if (cancel == null) {
                result.put("result", "취소 실패");
                problems.add("%s %s 취소 실패 — 미체결로 남음(HTS에서 취소 필요, 체결될 가격은 아니다)".formatted(name, orderNo));
            } else {
                result.put("result", "취소");
                expect(name + " — 취소 응답", text(cancel, "ord_no").length() > 0 && sameOrderNo(cancel.get("base_orig_ord_no"), orderNo),
                        "ord_no=%s, base_orig_ord_no=%s".formatted(text(cancel, "ord_no"), text(cancel, "base_orig_ord_no")));
            }
            pause();
            Map<String, Object> after = step(TrId.ORDER_EXECUTION_DETAIL, ACCOUNT_PATH, orderDetail("1"), side + "-cancelled");
            if (after != null && cancel != null) {
                Map<?, ?> row = rowOf(after, orderNo);
                expect(name + " — 취소 뒤 잔량 0·체결 0",
                        row != null && number(row, "ord_remnq") == 0 && number(row, "cntr_qty") == 0,
                        row == null ? "kt00007에 원주문 행 없음"
                                : "ord_remnq=%s, cntr_qty=%s".formatted(text(row, "ord_remnq"), text(row, "cntr_qty")));
                Map<?, ?> child = childRowOf(after, orderNo);
                result.put("cancelRow", child == null ? null : mask(child));
            }
            if (buy) {
                step(TrId.ORDER_EXECUTION_DETAIL, ACCOUNT_PATH, orderDetail("3"), side + "-cancelled-qry3");
                Map<String, Object> outstandingAfter = step(TrId.OUTSTANDING_ORDERS, ACCOUNT_PATH, outstanding(),
                        side + "-cancelled");
                if (outstandingAfter != null && cancel != null) {
                    expect(name + " — 취소 뒤 미체결에서 빠짐", rowOf(outstandingAfter, orderNo) == null,
                            "ka10075에 주문 " + orderNo + "이 남아 있음");
                }
                Map<String, Object> executions = step(TrId.EXECUTIONS, ACCOUNT_PATH, executions(symbol, "1"), side + "-cancelled");
                if (executions != null) {
                    expect(name + " — 체결 목록에 없음", rowOf(executions, orderNo) == null,
                            "ka10076에 체결되지 않은 주문 " + orderNo + "이 있음");
                }
            }
            return true;
        }

        /**
         * 체결 왕복 — 시장가 1주 매수 → 체결 확인 → 시장가 1주 매도. 체결 행(kt00007·ka10076)의 실제 모양이 목적이다.
         * 매수가 끝내 체결되지 않으면 취소하고 매도하지 않는다 — 원래 보유를 줄이지 않는다.
         */
        private void fillRoundTrip() {
            Map<String, Object> result = new LinkedHashMap<>();
            scenarios.put("fill-round-trip", result);
            String buyNo = order(TrId.ORDER_BUY, "", "3", "market-buy");
            result.put("buyOrderNo", buyNo);
            if (buyNo == null) {
                result.put("result", "시장가 매수 접수 안 됨");
                problems.add("체결 왕복: 시장가 매수가 접수되지 않았다");
                return;
            }
            pause();
            long bought = fillQueries("market-buy", buyNo, true);
            for (int check = 2; bought < 1 && check <= FILL_CHECKS; check++) {
                pause();
                bought = filledQuantity(step(TrId.ORDER_EXECUTION_DETAIL, ACCOUNT_PATH, orderDetail("1"),
                        "market-buy-check" + check), buyNo);
            }
            if (bought < 1) {
                // 체결이 안 보이면 취소한다. 그 사이 체결됐으면 취소가 거부되므로 한 번 더 확인한다
                Map<String, Object> cancel = cancel(buyNo, "market-buy");
                pause();
                bought = filledQuantity(step(TrId.ORDER_EXECUTION_DETAIL, ACCOUNT_PATH, orderDetail("1"),
                        "market-buy-after-cancel"), buyNo);
                if (bought < 1) {
                    result.put("result", cancel != null ? "매수 체결 안 됨 → 취소, 매도 안 함" : "매수 체결 안 됨·취소 실패");
                    problems.add(cancel != null
                            ? "체결 왕복: 시장가 매수 " + buyNo + "가 체결되지 않아 취소했다(매도 안 함)"
                            : "체결 왕복: 시장가 매수 " + buyNo + "의 체결·취소가 모두 확인되지 않았다 — HTS에서 확인 필요");
                    return;
                }
            }
            result.put("buyFilledQty", bought);
            if (limitSellSkipped) {
                // 보유가 없어 앞의 지정가 매도를 건너뛰었다(10/15 실측) — 방금 산 1주를 들고 있는 동안 지정가 매도·취소를 잰다
                holdingProbeShare = true;
                limitScenario(false, limitPriceUp(currentPrice, pct));
                holdingProbeShare = false;
            }
            String sellNo = order(TrId.ORDER_SELL, "", "3", "market-sell");
            if (sellNo == null) {
                pause();
                sellNo = order(TrId.ORDER_SELL, "", "3", "market-sell-retry");
            }
            result.put("sellOrderNo", sellNo);
            if (sellNo == null) {
                result.put("result", "매수 체결, 매도 실패");
                problems.add("체결 왕복: 시장가 매도 실패 — 모의 계좌에 %s 1주가 늘어난 채 남음(HTS에서 매도 필요)"
                        .formatted(StockNames.label(symbol)));
                return;
            }
            pause();
            long sold = fillQueries("market-sell", sellNo, false);
            result.put("sellFilledQty", sold);
            if (sold < 1) {
                result.put("result", "매수 %d주 체결 → 매도 접수(체결 미확인)".formatted(bought));
                problems.add("체결 왕복: 시장가 매도 " + sellNo + "의 체결이 조회에 보이지 않는다 — 장 마감 전 HTS에서 확인 필요");
            } else {
                result.put("result", "매수 %d주 체결 → 매도 %d주 체결".formatted(bought, sold));
            }
        }

        /**
         * 체결 뒤 조회 — 주문체결내역(주문순·체결만)·체결(종목, 매수 뒤에는 전체도)·미체결. 체결 행의 단가·잔량·필드를 점검하고,
         * 그 주문의 체결 수량(조회 중 가장 큰 값)을 돌려준다.
         */
        private long fillQueries(String label, String orderNo, boolean wide) {
            Map<String, Object> detail = step(TrId.ORDER_EXECUTION_DETAIL, ACCOUNT_PATH, orderDetail("1"), label + "-filled");
            long filled = filledQuantity(detail, orderNo);
            Map<?, ?> detailRow = rowOf(detail, orderNo);
            if (detailRow != null && filled >= 1) {
                expect("체결 왕복 — 주문내역 체결 단가·잔량", number(detailRow, "cntr_uv") > 0 && number(detailRow, "ord_remnq") == 0,
                        "cntr_uv=%s, ord_remnq=%s".formatted(text(detailRow, "cntr_uv"), text(detailRow, "ord_remnq")));
            }
            step(TrId.ORDER_EXECUTION_DETAIL, ACCOUNT_PATH, orderDetail("4"), label + "-filled-qry4");
            Map<String, Object> executions = step(TrId.EXECUTIONS, ACCOUNT_PATH, executions(symbol, "1"), label + "-filled");
            filled = Math.max(filled, filledQuantity(executions, orderNo));
            Map<?, ?> executionRow = rowOf(executions, orderNo);
            if (executionRow != null) {
                List<String> miss = missing(executionRow, EXECUTION_FIELDS);
                expect("체결 행 필드", miss.isEmpty(), "없는 필드 " + miss);
            }
            if (wide) {
                step(TrId.EXECUTIONS, ACCOUNT_PATH, executions("", "0"), label + "-filled-all");
                step(TrId.OUTSTANDING_ORDERS, ACCOUNT_PATH, outstanding(), label + "-filled");
            }
            return filled;
        }

        /** 기대 하나를 점검해 기록한다 — 어긋나면 사람이 확인할 일로 올린다. */
        private void expect(String name, boolean ok, String mismatch) {
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("name", name);
            check.put("ok", ok);
            if (!ok) {
                check.put("detail", mismatch);
                problems.add("점검 실패: %s — %s".formatted(name, mismatch));
            }
            checks.add(check);
        }

        /** 1주 주문(kt10000 매수·kt10001 매도). {@code trde_tp} 0 = 보통(지정가), 3 = 시장가(단가 비움 — 스펙 예시 그대로). */
        private String order(TrId trId, String price, String tradeType, String label) {
            return order(trId, price, tradeType, label, false);
        }

        /** @param noHoldingExpected 보유가 없어 거부되면(800033) 실측 실패로 세지 않는다 — 처음 내는 지정가 매도만 */
        private String order(TrId trId, String price, String tradeType, String label, boolean noHoldingExpected) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("dmst_stex_tp", "KRX");
            body.put("stk_cd", symbol);
            body.put("ord_qty", "1");
            body.put("ord_uv", price);
            body.put("trde_tp", tradeType);
            if ("3".equals(tradeType)) {
                body.put("cond_uv", "");
            }
            Map<String, Object> response = step(trId, ORDER_PATH, body, label, noHoldingExpected);
            return remember(response == null ? null : response.get("ord_no"));
        }

        /** 잔량 전량 취소(kt10003, 실패하면 1회 재시도). 성공하면 응답, 끝내 실패하면 null. 취소 주문의 새 번호도 우리 주문으로 적는다. */
        private Map<String, Object> cancel(String orderNo, String label) {
            Map<String, Object> body = Map.of(
                    "dmst_stex_tp", "KRX",
                    "orig_ord_no", orderNo,
                    "stk_cd", symbol,
                    "cncl_qty", "0");
            Map<String, Object> response = step(TrId.ORDER_CANCEL, ORDER_PATH, body, label + "-cancel");
            if (response == null) {
                pause();
                response = step(TrId.ORDER_CANCEL, ORDER_PATH, body, label + "-cancel-retry");
            }
            if (response != null) {
                remember(response.get("ord_no"));
            }
            return response;
        }

        /** 주문번호를 우리 주문으로 적고 돌려준다(비어 있으면 null). */
        private String remember(Object raw) {
            if (raw == null || String.valueOf(raw).isBlank()) {
                return null;
            }
            String no = String.valueOf(raw).trim();
            ourOrderNos.add(no);
            // 체결통보 처리(trading)가 이 번호를 실측 주문으로 알아보게 — 모르는 주문 WARN을 남기지 않는다(10/15)
            publisher.publishEvent(new ProbeOrderPlaced(no, clock.instant()));
            return no;
        }

        /** ka10075 미체결 — 스펙의 전체 조회(all_stk_tp 0, 거래소 통합). */
        private Map<String, Object> outstanding() {
            return Map.of("all_stk_tp", "0", "trde_tp", "0", "stk_cd", "", "stex_tp", "0");
        }

        /** kt00007 주문체결내역상세 — qry_tp 1 주문순·3 미체결·4 체결내역만, 오늘·전 거래소. */
        private Map<String, Object> orderDetail(String queryType) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ord_dt", "");
            body.put("qry_tp", queryType);
            body.put("stk_bond_tp", "0");
            body.put("sell_tp", "0");
            body.put("stk_cd", "");
            body.put("fr_ord_no", "");
            body.put("dmst_stex_tp", "%");
            return body;
        }

        /** ka10076 체결 — qry_tp 0 전체·1 종목, 거래소 통합. */
        private Map<String, Object> executions(String stockCode, String queryType) {
            return Map.of("stk_cd", stockCode, "qry_tp", queryType, "sell_tp", "0", "ord_no", "", "stex_tp", "0");
        }

        private Map<String, Object> step(TrId trId, String path, Map<String, Object> body, String label) {
            return step(trId, path, body, label, false);
        }

        private Map<String, Object> step(TrId trId, String path, Map<String, Object> body, String label,
                                         boolean noHoldingExpected) {
            seq++;
            String file = fileName(day, seq, trId.apiId() + "_" + label);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("seq", seq);
            entry.put("tr", trId.apiId());
            entry.put("label", label);
            entry.put("request", body);
            entry.put("file", file);
            Map<String, Object> response = null;
            try {
                response = client.call(trId, path, body);
                entry.put("return_code", response.get("return_code"));
                entry.put("return_msg", response.get("return_msg"));
                describeRows(entry, response);
                write(dir.resolve(file), mask(response));
            } catch (RuntimeException e) {
                // 키움 오류 응답(KiwoomApiException)뿐 아니라 네트워크·유량 대기 초과도 기록만 하고 다음 단계로 간다 —
                // 주문을 낸 뒤라면 취소·매도까지 반드시 가야 한다
                String message = e.getClass().getSimpleName() + ": " + SecretMasking.mask(String.valueOf(e.getMessage()));
                entry.put("error", message);
                if (noHoldingExpected && isNoHolding(message)) {
                    // 보유가 없으면 지정가 매도는 거부된다(10/15 실측, 10/13 국면 OFF 청산 뒤) — 계좌 상태이지 주문 경로 이상이 아니다
                    noHoldingRejected = true;
                    entry.put("expected", NO_HOLDING_RESULT);
                    write(dir.resolve(file), Map.of("error", message, "expected", NO_HOLDING_RESULT));
                    log.info("TR 실측(1.6) {} {} — 매도할 보유가 없어 접수 안 됨(실측 실패 아님{})", trId.apiId(), label,
                            fillRoundTrip ? ", 체결 왕복에서 산 1주로 다시 잰다" : ", 오늘은 건너뜀");
                } else {
                    failures++;
                    write(dir.resolve(file), Map.of("error", message));
                    log.warn("TR 실측(1.6) {} {} 실패: {}", trId.apiId(), label, message);
                }
            }
            steps.add(entry);
            return response;
        }

        /** 응답의 목록(첫 번째 배열 값) 행 수와, 그중 우리 주문(원주문·취소 주문) 행. */
        private void describeRows(Map<String, Object> entry, Map<String, Object> response) {
            for (Map.Entry<String, Object> field : response.entrySet()) {
                if (field.getValue() instanceof List<?> rows) {
                    entry.put("list", field.getKey());
                    entry.put("rows", rows.size());
                    List<Object> ours = rows.stream()
                            .filter(row -> row instanceof Map<?, ?> map && isOurs(map))
                            .map(TrProbeJob::mask)
                            .toList();
                    entry.put("ourOrderRows", ours);
                    if (!ours.isEmpty()) {
                        ourOrderSeen++;
                    }
                    return;
                }
            }
        }

        /** 주문번호·원주문번호 키 중 하나가 이번 실측의 주문번호인 행 — 취소 주문 행(원주문 = 우리 주문)도 잡는다(결함 P-2). */
        private boolean isOurs(Map<?, ?> row) {
            for (String key : ORDER_NO_KEYS) {
                Object value = row.get(key);
                for (String ours : ourOrderNos) {
                    if (sameOrderNo(value, ours)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private String finish(BigDecimal current) {
            long passed = checks.stream().filter(check -> Boolean.TRUE.equals(check.get("ok"))).count();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("date", day.toString());
            summary.put("symbol", symbol);
            summary.put("symbolLabel", StockNames.label(symbol));
            summary.put("currentPrice", current == null ? null : current.toPlainString());
            summary.put("pct", pct);
            summary.put("fillRoundTrip", fillRoundTrip);
            summary.put("orderNos", List.copyOf(ourOrderNos));
            summary.put("scenarios", scenarios);
            summary.put("checks", checks);
            summary.put("failures", failures);
            summary.put("problems", problems);
            summary.put("queriesShowingOurOrder", ourOrderSeen);
            summary.put("steps", steps);
            write(dir.resolve(fileName(day, 0, "summary")), summary);
            boolean ok = failures == 0 && problems.isEmpty();
            return "TR 실측(1.6) %s — %s %s. 점검 %d건 중 %d건 맞음, TR 호출 %d건 중 실패 %d.%s 결과 %s"
                    .formatted(ok ? "완료" : "일부 실패", StockNames.label(symbol), describeScenarios(), checks.size(), passed,
                            seq, failures, problems.isEmpty() ? "" : " 확인 필요: " + String.join("; ", problems) + ".",
                            dir);
        }

        /** 알림 한 줄용 — 예: "지정가 매수 95,100원 0064001 취소 · 지정가 매도 128,600원 0064003 취소 · 체결 왕복 매수 1주 체결 → 매도 1주 체결". */
        private String describeScenarios() {
            List<String> parts = new ArrayList<>();
            scenarios.forEach((name, value) -> {
                Map<?, ?> result = (Map<?, ?>) value;
                if (name.startsWith("limit-")) {
                    Object orderNo = result.get("orderNo");
                    parts.add("%s %,d원%s %s".formatted("limit-buy".equals(name) ? "지정가 매수" : "지정가 매도",
                            (Long) result.get("price"), orderNo == null ? "" : " " + orderNo, result.get("result")));
                } else {
                    parts.add("체결 왕복 " + result.get("result"));
                }
            });
            return parts.isEmpty() ? "주문 없음" : String.join(" · ", parts);
        }

        private void pause() {
            try {
                sleeper.sleep(SETTLE);
            } catch (InterruptedException e) {
                // 종료 중일 수 있다 — 기다림만 건너뛰고 남은 단계(특히 취소·매도)는 그대로 시도한다
                Thread.currentThread().interrupt();
            }
        }

        /** 저장 실패는 실측을 멈추지 않는다(주문을 낸 뒤라면 취소·매도까지 가야 한다) — 경고와 실패 수만 남긴다. */
        private void write(Path file, Object value) {
            try {
                Files.write(file, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
            } catch (IOException e) {
                failures++;
                log.warn("TR 실측(1.6) 결과 저장 실패: {} — {}", file.toAbsolutePath(), e.getMessage());
            }
        }
    }

    /** 대기 함수 — 운영은 {@link Thread#sleep(Duration)}, 테스트는 기록만 한다. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}

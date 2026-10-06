package com.autostock.execution;

import com.autostock.common.event.TrProbeCompleted;
import com.autostock.common.util.KiwoomNumbers;
import com.autostock.common.util.MarketConstants;
import com.autostock.common.util.SecretMasking;
import com.autostock.common.util.StockNames;
import com.autostock.kiwoom.KiwoomProperties;
import com.autostock.kiwoom.KiwoomRestClient;
import com.autostock.kiwoom.TrId;
import com.autostock.risk.KillSwitch;
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
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 실행 계획 1.6 — UNKNOWN 자동 해소에 쓸 체결·미체결 TR 장중 실측. 정해진 날 한 번, 모의 서버에서만 돈다
 * (aiDoc/market-hours-automation.md).
 *
 * <p>순서: 현재가(ka10001) → 현재가보다 {@code discountPct}% 낮은 지정가 1주 매수(kt10000, 체결되지 않게) →
 * 미체결(ka10075)·주문체결내역상세(kt00007)·체결(ka10076)을 파라미터별로 조회 → 취소(kt10003) → 다시 조회.
 * 응답 원문은 계좌번호를 가린 뒤 {@code tr_probe_{yyyyMMdd}_{순번}_{TR}_{조건}.json}으로 남기고, 요약
 * ({@code _00_summary.json})에 우리 주문번호가 어느 조회에 어떤 모양으로 나왔는지 적는다. 끝나면
 * {@link TrProbeCompleted}를 발행한다(monitor가 텔레그램으로 알림).
 *
 * <p>안전장치:
 * <ul>
 *   <li>{@code execution.tr-probe.enabled=true}이고 오늘이 {@code date}일 때만, {@code at}~14:50 사이 첫 분에 한 번</li>
 *   <li>키움 REST 주소가 모의 서버({@value #MOCK_HOST})가 아니면 절대 돌지 않는다</li>
 *   <li>킬스위치가 켜져 있으면 미룬다(다음 분에 다시 본다)</li>
 *   <li>그날 요약 파일이 이미 있으면 다시 돌지 않는다 — 재기동해도 한 번</li>
 * </ul>
 *
 * <p>이 주문은 앱의 주문 장부(trading)를 거치지 않는다. 주문 한도·일련번호를 쓰지 않고, 체결통보·대사에서는
 * "모르는 주문"으로 무시된다(OrderNoticeHandler "수동 주문 등으로 추정" 경로). 일별 성과의 주문 수에도 안 잡힌다.
 */
@Component
public class TrProbeJob {

    private static final Logger log = LoggerFactory.getLogger(TrProbeJob.class);

    static final String MOCK_HOST = "mockapi.kiwoom.com";
    /** 이 시각 뒤로는 시작하지 않는다 — 장 마감 동시호가(15:20~) 전에 주문·취소를 끝낸다. */
    static final LocalTime DEADLINE = LocalTime.of(14, 50);

    private static final String ORDER_PATH = "/api/dostk/ordr";
    private static final String ACCOUNT_PATH = "/api/dostk/acnt";
    private static final String STOCK_INFO_PATH = "/api/dostk/stkinfo";
    /** 주문 접수·취소가 조회에 반영되기를 기다리는 시간. */
    private static final Duration SETTLE = Duration.ofSeconds(3);
    /** 응답 원문에서 값을 가리는 키(소문자 부분 일치) — 계좌번호·관리사원번호. 공개 리포에 남기 때문이다. */
    private static final List<String> MASKED_KEY_PARTS = List.of("acnt", "empno");

    private final TrProbeProperties properties;
    private final KiwoomRestClient client;
    private final KiwoomProperties kiwoomProperties;
    private final KillSwitch killSwitch;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private final Sleeper sleeper;
    private final AtomicBoolean finished = new AtomicBoolean();
    private volatile String lastDeferReason;

    @Autowired
    public TrProbeJob(TrProbeProperties properties, KiwoomRestClient client, KiwoomProperties kiwoomProperties,
                      KillSwitch killSwitch, ObjectMapper objectMapper, ApplicationEventPublisher publisher,
                      Clock clock) {
        this(properties, client, kiwoomProperties, killSwitch, objectMapper, publisher, clock, Thread::sleep);
    }

    /** 테스트용 — 대기를 실제로 잠들지 않게 바꿔 끼운다. */
    TrProbeJob(TrProbeProperties properties, KiwoomRestClient client, KiwoomProperties kiwoomProperties,
               KillSwitch killSwitch, ObjectMapper objectMapper, ApplicationEventPublisher publisher,
               Clock clock, Sleeper sleeper) {
        this.properties = properties;
        this.client = client;
        this.kiwoomProperties = kiwoomProperties;
        this.killSwitch = killSwitch;
        this.objectMapper = objectMapper;
        this.publisher = publisher;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** 평일 9~14시 매분 — 실측할 날·시각이 됐는지 보고, 됐으면 한 번 돈다. */
    @Scheduled(cron = "0 * 9-14 * * MON-FRI", zone = "Asia/Seoul")
    public void tick() {
        if (!properties.enabled() || properties.date() == null || finished.get()) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(MarketConstants.KST));
        LocalDate today = now.toLocalDate();
        LocalTime time = now.toLocalTime();
        if (!today.equals(properties.date()) || time.isBefore(properties.at()) || !time.isBefore(DEADLINE)) {
            return;
        }
        Path dir = Path.of(properties.outputDir());
        Path summary = dir.resolve(fileName(today, 0, "summary"));
        if (Files.exists(summary)) {
            finished.set(true);
            log.info("TR 실측(1.6): 오늘 결과가 이미 있다 — 다시 돌지 않는다({})", summary.toAbsolutePath());
            return;
        }
        String host = URI.create(kiwoomProperties.restBaseUrl()).getHost();
        if (!MOCK_HOST.equalsIgnoreCase(host)) {
            finished.set(true);
            log.error("TR 실측(1.6) 거부 — 키움 주소가 모의 서버가 아니다({}). 실서버에서는 돌지 않는다", host);
            return;
        }
        int pct = properties.discountPct();
        if (pct < 5 || pct > 29) {
            finished.set(true);
            log.error("TR 실측(1.6) 거부 — discount-pct {}: 5~29만 허용(가격제한폭 ±30% 안, 체결되지 않을 만큼 낮게)", pct);
            return;
        }
        if (killSwitch.isEngaged()) {
            defer("킬스위치 작동 중");
            return;
        }
        finished.set(true);
        new Probe(today, dir, pct).run();
    }

    private void defer(String reason) {
        if (!reason.equals(lastDeferReason)) {
            lastDeferReason = reason;
            log.warn("TR 실측(1.6) 미룸 — {}. 다음 분에 다시 본다", reason);
        }
    }

    /** 현재가에서 pct% 내린 값을 호가 단위로 내림한 지정가. */
    static long limitPrice(BigDecimal currentPrice, int discountPct) {
        long raw = currentPrice.multiply(BigDecimal.valueOf(100L - discountPct))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR).longValueExact();
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

    /** 계좌번호·관리사원번호 값을 가린 사본(키 이름은 그대로 둔다 — 필드 확정이 목적이다). */
    static Object mask(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                String key = String.valueOf(k);
                boolean secret = MASKED_KEY_PARTS.stream().anyMatch(key.toLowerCase(Locale.ROOT)::contains);
                copy.put(key, secret && v != null && !String.valueOf(v).isBlank() ? "****" : mask(v));
            });
            return copy;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(TrProbeJob::mask).toList();
        }
        return value;
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

    /** 한 번의 실측 실행 — 순번·결과를 모은다. */
    private final class Probe {
        private final LocalDate day;
        private final Path dir;
        private final int pct;
        private final String symbol = properties.symbol();
        private final List<Map<String, Object>> steps = new ArrayList<>();
        private int seq;
        private int failures;
        private String orderNo;
        private int ourOrderSeen;

        Probe(LocalDate day, Path dir, int pct) {
            this.day = day;
            this.dir = dir;
            this.pct = pct;
        }

        void run() {
            String summaryLine;
            boolean success;
            try {
                Files.createDirectories(dir);
                log.info("TR 실측(1.6) 시작 — {} 1주를 현재가보다 {}% 낮은 지정가로 매수 → 조회 → 취소 → 조회. 결과 폴더 {}",
                        StockNames.label(symbol), pct, dir.toAbsolutePath());
                summaryLine = probe();
                success = failures == 0 && orderNo != null;
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
            publisher.publishEvent(new TrProbeCompleted(day, success, summaryLine, clock.instant()));
        }

        private String probe() {
            Map<String, Object> quote = step(TrId.STOCK_PRICE, STOCK_INFO_PATH, Map.of("stk_cd", symbol), "quote");
            BigDecimal current = quote == null ? BigDecimal.ZERO : KiwoomNumbers.toBigDecimal(quote.get("cur_prc")).abs();
            if (current.signum() <= 0) {
                return finish(null, 0, "현재가를 읽지 못해 주문하지 않았다");
            }
            long limit = limitPrice(current, pct);
            Map<String, Object> buy = step(TrId.ORDER_BUY, ORDER_PATH, Map.of(
                    "dmst_stex_tp", "KRX",
                    "stk_cd", symbol,
                    "ord_qty", "1",
                    "ord_uv", String.valueOf(limit),
                    "trde_tp", "0"), "buy");
            Object no = buy == null ? null : buy.get("ord_no");
            orderNo = no == null || String.valueOf(no).isBlank() ? null : String.valueOf(no).trim();
            if (orderNo != null) {
                pause();
            }
            queryOpen();
            String cancelResult = "주문이 없어 취소 생략";
            if (orderNo != null) {
                Map<String, Object> cancelBody = Map.of(
                        "dmst_stex_tp", "KRX",
                        "orig_ord_no", orderNo,
                        "stk_cd", symbol,
                        "cncl_qty", "0");
                Map<String, Object> cancel = step(TrId.ORDER_CANCEL, ORDER_PATH, cancelBody, "cancel");
                if (cancel == null) {
                    // 취소가 안 되면 지정가 주문이 장 끝까지 남는다(체결될 가격은 아니다) — 한 번 더 시도한다
                    pause();
                    cancel = step(TrId.ORDER_CANCEL, ORDER_PATH, cancelBody, "cancel-retry");
                }
                cancelResult = cancel == null
                        ? "취소 실패 — 주문 " + orderNo + "이 미체결로 남음(HTS에서 취소 필요)"
                        : "취소 " + cancel.getOrDefault("return_msg", "응답");
                pause();
                queryAfterCancel();
            }
            return finish(current, limit, cancelResult);
        }

        /** 주문이 미체결로 살아 있을 때 — 앱이 지금 쓰는 파라미터와 스펙의 파라미터를 나란히 조회한다. */
        private void queryOpen() {
            String today = day.format(DateTimeFormatter.BASIC_ISO_DATE);
            // ka10075 미체결 — 스펙: all_stk_tp 0 전체·1 종목, stex_tp 0 통합·1 KRX·2 NXT
            step(TrId.OUTSTANDING_ORDERS, ACCOUNT_PATH, Map.of("all_stk_tp", "1", "trde_tp", "0", "stex_tp", "0"), "app");
            step(TrId.OUTSTANDING_ORDERS, ACCOUNT_PATH, outstanding("0", "", "0"), "all");
            step(TrId.OUTSTANDING_ORDERS, ACCOUNT_PATH, outstanding("1", symbol, "0"), "symbol");
            step(TrId.OUTSTANDING_ORDERS, ACCOUNT_PATH, outstanding("0", "", "1"), "krx");
            step(TrId.OUTSTANDING_ORDERS, ACCOUNT_PATH, outstanding("0", "", "2"), "nxt");
            // kt00007 주문체결내역상세 — qry_tp 1 주문순·3 미체결, dmst_stex_tp %·KRX·NXT·SOR
            step(TrId.ORDER_EXECUTION_DETAIL, ACCOUNT_PATH, orderDetail("", "1", "%"), "all");
            step(TrId.ORDER_EXECUTION_DETAIL, ACCOUNT_PATH, orderDetail(today, "1", "%"), "date");
            step(TrId.ORDER_EXECUTION_DETAIL, ACCOUNT_PATH, orderDetail("", "3", "%"), "open");
            step(TrId.ORDER_EXECUTION_DETAIL, ACCOUNT_PATH, orderDetail("", "1", "KRX"), "krx");
            step(TrId.ORDER_EXECUTION_DETAIL, ACCOUNT_PATH, orderDetail("", "1", "NXT"), "nxt");
            step(TrId.ORDER_EXECUTION_DETAIL, ACCOUNT_PATH, orderDetail("", "1", "SOR"), "sor");
            // ka10076 체결 — 미체결 주문이라 체결 행은 없어야 한다(빈 목록 모양 확인)
            step(TrId.EXECUTIONS, ACCOUNT_PATH, executions(symbol, "1", "0"), "symbol");
            step(TrId.EXECUTIONS, ACCOUNT_PATH, executions("", "0", "0"), "all");
            step(TrId.EXECUTIONS, ACCOUNT_PATH, executions(symbol, "1", "1"), "krx");
            step(TrId.EXECUTIONS, ACCOUNT_PATH, executions(symbol, "1", "2"), "nxt");
        }

        /** 취소 뒤 — 미체결 목록에서 빠지는지, 주문내역에 취소가 어떻게 남는지. */
        private void queryAfterCancel() {
            step(TrId.OUTSTANDING_ORDERS, ACCOUNT_PATH, Map.of("all_stk_tp", "1", "trde_tp", "0", "stex_tp", "0"),
                    "app-after-cancel");
            step(TrId.OUTSTANDING_ORDERS, ACCOUNT_PATH, outstanding("0", "", "0"), "all-after-cancel");
            step(TrId.ORDER_EXECUTION_DETAIL, ACCOUNT_PATH, orderDetail("", "1", "%"), "all-after-cancel");
            step(TrId.EXECUTIONS, ACCOUNT_PATH, executions(symbol, "1", "0"), "symbol-after-cancel");
        }

        private Map<String, Object> outstanding(String allStock, String stockCode, String exchange) {
            return Map.of("all_stk_tp", allStock, "trde_tp", "0", "stk_cd", stockCode, "stex_tp", exchange);
        }

        private Map<String, Object> orderDetail(String orderDate, String queryType, String exchange) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ord_dt", orderDate);
            body.put("qry_tp", queryType);
            body.put("stk_bond_tp", "0");
            body.put("sell_tp", "0");
            body.put("stk_cd", "");
            body.put("fr_ord_no", "");
            body.put("dmst_stex_tp", exchange);
            return body;
        }

        private Map<String, Object> executions(String stockCode, String queryType, String exchange) {
            return Map.of("stk_cd", stockCode, "qry_tp", queryType, "sell_tp", "0", "ord_no", "", "stex_tp", exchange);
        }

        private Map<String, Object> step(TrId trId, String path, Map<String, Object> body, String label) {
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
                // 주문을 낸 뒤라면 취소까지 반드시 가야 한다
                failures++;
                String message = e.getClass().getSimpleName() + ": " + SecretMasking.mask(String.valueOf(e.getMessage()));
                entry.put("error", message);
                write(dir.resolve(file), Map.of("error", message));
                log.warn("TR 실측(1.6) {} {} 실패: {}", trId.apiId(), label, message);
            }
            steps.add(entry);
            return response;
        }

        /** 응답의 목록(첫 번째 배열 값) 행 수와, 그중 우리 주문번호 행. */
        private void describeRows(Map<String, Object> entry, Map<String, Object> response) {
            for (Map.Entry<String, Object> field : response.entrySet()) {
                if (field.getValue() instanceof List<?> rows) {
                    entry.put("list", field.getKey());
                    entry.put("rows", rows.size());
                    List<Object> ours = rows.stream()
                            .filter(row -> row instanceof Map<?, ?> map && sameOrderNo(map.get("ord_no"), orderNo))
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

        private String finish(BigDecimal current, long limit, String cancelResult) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("date", day.toString());
            summary.put("symbol", symbol);
            summary.put("symbolLabel", StockNames.label(symbol));
            summary.put("currentPrice", current == null ? null : current.toPlainString());
            summary.put("discountPct", pct);
            summary.put("limitPrice", limit);
            summary.put("orderNo", orderNo);
            summary.put("cancel", cancelResult);
            summary.put("failures", failures);
            summary.put("queriesShowingOurOrder", ourOrderSeen);
            summary.put("steps", steps);
            write(dir.resolve(fileName(day, 0, "summary")), summary);
            String order = orderNo == null
                    ? "주문 접수 안 됨"
                    : "%,d원 지정가(현재가 %s의 -%d%%) 주문 %s".formatted(limit,
                            current == null ? "?" : "%,d".formatted(current.longValue()), pct, orderNo);
            return "TR 실측(1.6) %s — %s 1주 %s, TR 호출 %d건 중 실패 %d, 우리 주문이 보인 조회 %d건, %s. 결과 %s/tr_probe_%s_*.json"
                    .formatted(failures == 0 && orderNo != null ? "완료" : "일부 실패", StockNames.label(symbol), order,
                            seq, failures, ourOrderSeen, cancelResult, properties.outputDir(),
                            day.format(DateTimeFormatter.BASIC_ISO_DATE));
        }

        private void pause() {
            try {
                sleeper.sleep(SETTLE);
            } catch (InterruptedException e) {
                // 종료 중일 수 있다 — 기다림만 건너뛰고 남은 단계(특히 취소)는 그대로 시도한다
                Thread.currentThread().interrupt();
            }
        }

        /** 저장 실패는 실측을 멈추지 않는다(주문을 낸 뒤라면 취소까지 가야 한다) — 경고와 실패 수만 남긴다. */
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

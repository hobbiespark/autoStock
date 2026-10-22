package com.autostock.strategy;

import com.autostock.common.event.StrategyRunCompleted;
import com.autostock.common.util.StockCode;
import com.autostock.common.util.StockNames;
import com.autostock.market.MarketDataPort;
import com.autostock.portfolio.PositionBook;
import com.autostock.portfolio.StrategyBook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 종가 → 다음 날 시가(ON-CLOSE) — 모의투자 다전략 중 하나(2026-10-16, aiDoc/multi-strategy-paper-2026-10-16.md 3-2).
 *
 * <p>흐름(거래일):
 * <ol>
 *   <li>09:00:30 — 어제 산 보유 전부 매도(최우선 매수호가 × 0.98). 09:10·09:30, 그 뒤 30분마다 남은 것을 다시 낸다.</li>
 *   <li>15:15 — 후보 선정: 등락률 상위를 종목조건 4가지로 받아 교집합 → 스팩 제외 → 등락률 +5~+20% → 거래대금 큰 순서로
 *       현재가를 확인해 "고가의 97% 이상 · 시가보다 위"인 처음 3종목.</li>
 *   <li>15:26 — 종가 단일가 중 매수(15:15 현재가 × 1.05 지정가 → 종가에 체결). 15:26인 이유: 미체결 자동 취소가 5분이라
 *       15:21에 내면 15:30 체결 전에 취소된다.</li>
 * </ol>
 * 정규장 안에서만 한다(NXT·시간외는 미실측 — D-21).
 */
@Component
public class OvernightCloseStrategy {

    private static final Logger log = LoggerFactory.getLogger(OvernightCloseStrategy.class);
    static final String STRATEGY_ID = "ON-CLOSE";
    private static final String HORIZON = "DAY";
    /** 아침 매도(재시도 포함)가 이 시각 뒤에 돌면(절전 복귀로 늦게 깨어남) 건너뛴다 — 연속매매 끝(2026-10-21). */
    static final LocalTime SELL_DEADLINE = LocalTime.of(15, 20);
    /** 15:15 후보 선정이 이 시각 뒤에 돌면 건너뛴다 — 15:26 종가 매수 전에 끝나야 의미가 있다. */
    static final LocalTime SCAN_DEADLINE = LocalTime.of(15, 25);
    /** 15:26 종가 매수가 이 시각 뒤에 돌면 건너뛴다 — 15:30 종가 체결 전. */
    static final LocalTime BUY_DEADLINE = LocalTime.of(15, 29);

    private final OvernightCloseProperties properties;
    private final LiveMarket market;
    private final StrategyBook book;
    private final SignalOutbox outbox;
    private final StrategyGuard guard;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    /** 오늘 고른 종목(15:15) — 15:26에 산다. */
    private volatile Picks picks = new Picks(null, List.of());

    /** 하루 후보. */
    record Picks(LocalDate day, List<Pick> items) {
    }

    /** 고른 종목 — scanPrice는 15:15 현재가. */
    record Pick(String code, String name, BigDecimal scanPrice, BigDecimal changeRate, BigDecimal tradingValue,
                BigDecimal high, BigDecimal open) {
    }

    public OvernightCloseStrategy(OvernightCloseProperties properties, LiveMarket market, StrategyBook book,
                                  SignalOutbox outbox, StrategyGuard guard, ApplicationEventPublisher publisher,
                                  Clock clock) {
        this.properties = properties;
        this.market = market;
        this.book = book;
        this.outbox = outbox;
        this.guard = guard;
        this.publisher = publisher;
        this.clock = clock;
    }

    /** 09:00:30 — 어제 산 보유 매도. */
    @Scheduled(cron = "30 0 9 * * MON-FRI", zone = "Asia/Seoul")
    public void morningExit() {
        if (!guard.active(properties.enabled()) || guard.tooLate(STRATEGY_ID + " 09:00:30 아침 매도", SELL_DEADLINE)) {
            return;
        }
        Map<StockCode, PositionBook.Position> held = book.positions(STRATEGY_ID);
        if (held.isEmpty()) {
            return;
        }
        List<String> parts = new ArrayList<>();
        held.forEach((code, position) -> {
            BigDecimal bid = market.bestBid(code.value());
            parts.add("%s %d주(평단 %s → 호가 %s)".formatted(StockNames.label(code), position.quantity(),
                    position.avgPrice() == null ? "미상" : position.avgPrice().setScale(0, RoundingMode.HALF_UP),
                    bid == null ? "N/A" : bid.toPlainString()));
            sell(code.value(), bid, "다음 날 아침 매도");
        });
        String line = "%s %s 아침 매도 %d종목 — %s".formatted(STRATEGY_ID, guard.today(), held.size(),
                String.join(" · ", parts));
        log.info(line);
        publisher.publishEvent(new StrategyRunCompleted(STRATEGY_ID, guard.today(), line, 0, clock.instant()));
    }

    /** 아침 매도가 체결되지 않은 보유를 다시 낸다(미체결 매도가 남아 있으면 RiskGate가 막는다). */
    @Scheduled(cron = "0 10,30 9 * * MON-FRI", zone = "Asia/Seoul")
    @Scheduled(cron = "0 0/30 10-14 * * MON-FRI", zone = "Asia/Seoul")
    public void retryExit() {
        if (!guard.active(properties.enabled()) || guard.tooLate(STRATEGY_ID + " 아침 매도 재시도", SELL_DEADLINE)) {
            return;
        }
        Map<StockCode, PositionBook.Position> held = book.positions(STRATEGY_ID);
        if (held.isEmpty()) {
            return;
        }
        log.warn("{}: 아직 보유 {}종목 — 매도 다시: {}", STRATEGY_ID, held.size(),
                held.keySet().stream().map(StockNames::label).collect(Collectors.joining(", ")));
        held.keySet().forEach(code -> sell(code.value(), market.bestBid(code.value()), "아침 매도 재시도"));
    }

    /** 15:15 — 후보 선정. */
    @Scheduled(cron = "0 15 15 * * MON-FRI", zone = "Asia/Seoul")
    public void scan() {
        if (!guard.active(properties.enabled()) || guard.tooLate(STRATEGY_ID + " 15:15 후보 선정", SCAN_DEADLINE)) {
            return;
        }
        LocalDate today = guard.today();
        List<List<MarketDataPort.RankedStock>> lists = new ArrayList<>();
        for (String condition : properties.stockConditions()) {
            List<MarketDataPort.RankedStock> rows = market.risingRanking(condition);
            if (rows.isEmpty()) {
                String line = "%s %s 후보 없음 — 등락률 상위(종목조건 %s) 조회 실패·빈 응답".formatted(STRATEGY_ID, today,
                        condition);
                log.warn(line);
                picks = new Picks(today, List.of());
                publisher.publishEvent(new StrategyRunCompleted(STRATEGY_ID, today, line, 1, clock.instant()));
                return;
            }
            lists.add(rows);
        }
        List<MarketDataPort.RankedStock> ranked = candidates(lists, properties);
        Set<String> heldNow = book.positions(STRATEGY_ID).keySet().stream().map(StockCode::value)
                .collect(Collectors.toSet());
        int[] checked = {0};
        List<Pick> chosen = choose(ranked, heldNow, properties, code -> {
            checked[0]++;
            return market.quote(code);
        });
        picks = new Picks(today, chosen);
        String detail = chosen.isEmpty() ? "선택 없음"
                : chosen.stream().map(p -> "%s(+%s%%, 거래대금 %s억)".formatted(StockNames.label(p.code()),
                        p.changeRate().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                        p.tradingValue().divide(BigDecimal.valueOf(100_000_000), 0, RoundingMode.HALF_UP)))
                .collect(Collectors.joining(" · "));
        String line = "%s %s 후보 — 순위 교집합 %d · 등락률 +%s~+%s%% %d · 현재가 확인 %d → 선택 %d: %s".formatted(
                STRATEGY_ID, today, intersectionSize(lists), num(properties.minChangePct()),
                num(properties.maxChangePct()), ranked.size(), checked[0], chosen.size(), detail);
        log.info(line);
        publisher.publishEvent(new StrategyRunCompleted(STRATEGY_ID, today, line, 0, clock.instant()));
    }

    /** 15:26 — 종가 단일가 매수. */
    @Scheduled(cron = "0 26 15 * * MON-FRI", zone = "Asia/Seoul")
    public void buyAtClose() {
        if (!guard.active(properties.enabled()) || guard.tooLate(STRATEGY_ID + " 15:26 종가 매수", BUY_DEADLINE)) {
            return;
        }
        Picks today = picks;
        if (today.day() == null || !today.day().equals(guard.today())) {
            return;
        }
        for (Pick pick : today.items()) {
            if (book.holds(STRATEGY_ID, new StockCode(pick.code()))) {
                continue;
            }
            Map<String, String> metrics = new LinkedHashMap<>();
            metrics.put("scanPrice", pick.scanPrice().toPlainString());
            metrics.put("changeRate", pick.changeRate().toPlainString());
            metrics.put("high", pick.high().toPlainString());
            metrics.put("open", pick.open().toPlainString());
            metrics.put("tradingValue", pick.tradingValue().toPlainString());
            // 슬리피지 기준 = 15:15 현재가(판단 시점). 지정가는 그 ×(1 + 여유)라 종가에 체결된다
            outbox.buy(STRATEGY_ID, HORIZON, pick.code(), SignalOutbox.times(pick.scanPrice(), 1 + properties.buyPremium()),
                    pick.scanPrice(), 1.0, "종가 매수(단일가) — 15:15 등락률 +" + pick.changeRate().toPlainString() + "%, 고가 근처 마감", metrics);
        }
        if (!today.items().isEmpty()) {
            log.info("{}: 종가 단일가 매수 신호 {}종목", STRATEGY_ID, today.items().size());
        }
    }

    private void sell(String symbol, BigDecimal bid, String reason) {
        outbox.sell(STRATEGY_ID, HORIZON, symbol, SignalOutbox.times(bid, 1 - properties.sellDiscount()), bid, reason,
                Map.of("bestBid", bid == null ? "N/A" : bid.toPlainString()));
    }

    /**
     * 교집합 → 스팩 제외 → 등락률 범위 → 거래대금(현재가 × 거래량) 내림차순.
     */
    static List<MarketDataPort.RankedStock> candidates(List<List<MarketDataPort.RankedStock>> lists,
                                                       OvernightCloseProperties p) {
        if (lists.isEmpty()) {
            return List.of();
        }
        Set<String> common = lists.get(0).stream().map(MarketDataPort.RankedStock::code).collect(Collectors.toSet());
        for (int i = 1; i < lists.size(); i++) {
            common.retainAll(lists.get(i).stream().map(MarketDataPort.RankedStock::code).collect(Collectors.toSet()));
        }
        BigDecimal min = BigDecimal.valueOf(p.minChangePct());
        BigDecimal max = BigDecimal.valueOf(p.maxChangePct());
        return lists.get(0).stream()
                .filter(r -> common.contains(r.code()))
                .filter(r -> r.name() == null || !r.name().contains("스팩"))
                .filter(r -> r.changeRate() != null && r.changeRate().compareTo(min) >= 0
                        && r.changeRate().compareTo(max) <= 0)
                .filter(r -> r.current() != null && r.current().signum() > 0)
                .sorted(Comparator.comparing(MarketDataPort.RankedStock::tradingValue).reversed())
                .toList();
    }

    /**
     * 거래대금 큰 순서로 현재가를 확인해 "현재가 ≥ 고가 × nearHighRatio, 현재가 > 시가"인 처음 maxPicks종목.
     * 확인은 maxQuoteChecks종목까지. 아침 매도가 안 돼 아직 들고 있는 종목은 건너뛴다.
     */
    static List<Pick> choose(List<MarketDataPort.RankedStock> ranked, Set<String> heldNow, OvernightCloseProperties p,
                             Function<String, MarketDataPort.StockQuote> quotes) {
        List<Pick> chosen = new ArrayList<>();
        int checks = 0;
        for (MarketDataPort.RankedStock r : ranked) {
            if (chosen.size() >= p.maxPicks() || checks >= p.maxQuoteChecks()) {
                break;
            }
            if (heldNow.contains(r.code())) {
                continue;
            }
            checks++;
            MarketDataPort.StockQuote q = quotes.apply(r.code());
            if (q == null || q.current() == null || q.high() == null || q.open() == null) {
                continue;
            }
            boolean nearHigh = q.current().compareTo(q.high().multiply(BigDecimal.valueOf(p.nearHighRatio()))) >= 0;
            boolean upCandle = q.current().compareTo(q.open()) > 0;
            if (nearHigh && upCandle) {
                chosen.add(new Pick(r.code(), r.name(), q.current(), r.changeRate(), r.tradingValue(), q.high(), q.open()));
            }
        }
        return chosen;
    }

    private static int intersectionSize(List<List<MarketDataPort.RankedStock>> lists) {
        Set<String> common = lists.get(0).stream().map(MarketDataPort.RankedStock::code).collect(Collectors.toSet());
        for (int i = 1; i < lists.size(); i++) {
            common.retainAll(lists.get(i).stream().map(MarketDataPort.RankedStock::code).collect(Collectors.toSet()));
        }
        return common.size();
    }

    private static String num(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    /** 시험용. */
    Picks picks() {
        return picks;
    }
}

package com.autostock.market;

import com.autostock.common.util.StockCode;
import com.autostock.common.util.StockNames;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 종목명 사전 담당 — 화면·알림·로그가 종목을 "삼성전자(005930)"로 보여줄 수 있게 {@link StockNames}를 채운다
 * (2026-10-02 사용자 요구 "종목코드와 종목명은 항상 같이 표시", aiDoc/stock-names.md).
 *
 * <ol>
 *   <li>기동: DB 사전({@code stock_names}, V10)을 올린다 — 재기동 직후·키움 장애 중에도 이름이 바로 보인다.</li>
 *   <li>설정 종목(C3 매매 대상·분봉 적재 대상) 중 이름이 없는 것을 조회 대기열에 넣는다.</li>
 *   <li>표시하다 이름을 모르는 종목을 만나면({@link StockNames#onMiss}) 대기열에 넣는다.</li>
 *   <li>2초마다 대기열을 최대 5건씩 키움 ka10001(stk_nm)로 조회한다 — ka10001은 매매의 현재가 조회와 TR 유량
 *       (초당 1건)을 나눠 쓰므로 조금씩만 쓴다. 실패한 종목은 대기열에 남겨 30분 뒤에 스스로 다시 조회하고,
 *       {@value #MAX_ATTEMPTS}번 연달아 실패하면 다음 요청(표시·평일 08:10 갱신)까지 둔다. 2026-10-15 로그 점검: 예전에는 실패하면
 *       대기열에서 빠져 "30분 뒤 다시 조회"가 실제로는 일어나지 않았다(기동 07:53 키움 1631로 실패한 14건).</li>
 *   <li>다른 곳에서 배운 이름(시세 어댑터의 ka10001, 잔고 kt00018의 stk_nm, 공시 기업명)도 {@link StockNames#onLearn}으로
 *       받아 DB에 남긴다. <b>저장은 이 클래스의 2초 주기 작업이 모아서 한다</b> — 이름을 배운 호출 스레드(매매 판단,
 *       공시 블랙리스트 등록 트랜잭션)에서 DB를 쓰면 그 흐름이 느려지거나, 저장 실패가 바깥 트랜잭션을
 *       롤백 전용으로 만들 수 있다.</li>
 *   <li>평일 08:10에 확인한 지 7일이 지난 이름을 다시 조회한다(종목명 변경 대비).</li>
 * </ol>
 *
 * <p>표시 전용이다. 이 클래스가 실패해도 매매는 그대로 돌고, 표시만 "종목명 미확인(코드)"이 된다.
 */
@Service
public class StockNameDirectory {

    private static final Logger log = LoggerFactory.getLogger(StockNameDirectory.class);

    /** 이 기간이 지난 이름은 다시 조회한다. */
    static final Duration REFRESH_AFTER = Duration.ofDays(7);
    /** 조회 실패(이름 없음 포함) 뒤 다시 조회하기까지의 간격 — 없는 코드를 계속 두드리지 않는다. */
    static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(30);
    /** 한 번에 조회하는 최대 건수 — ka10001 유량을 매매 조회와 나눠 쓴다. */
    static final int MAX_LOOKUPS_PER_RUN = 5;
    /** 연달아 실패하면 스스로 다시 조회하지 않는 횟수 — 없는 코드를 하루 종일 두드리지 않는다. */
    static final int MAX_ATTEMPTS = 3;

    private final StockNameRepository repository;
    private final MarketDataPort marketData;
    private final Clock clock;
    private final List<String> configuredSymbols;
    /** 조회 대기 — 값은 "이미 이름이 있어도 다시 조회"(7일 갱신) 여부. */
    private final Map<String, Boolean> pending = new ConcurrentHashMap<>();
    private final Map<String, Instant> retryNotBefore = new ConcurrentHashMap<>();
    /** 연달아 실패한 횟수 — 성공하거나 새 요청(평일 갱신)이 오면 처음부터 센다. */
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();
    /** 배웠지만 아직 DB에 남기지 않은 이름 — 같은 종목은 마지막 값만 남는다. */
    private final Map<String, Learned> unsaved = new ConcurrentHashMap<>();

    private record Learned(String name, StockNames.Source source) {
    }

    public StockNameDirectory(StockNameRepository repository, MarketDataPort marketData, Clock clock,
                              @Value("${strategy.c3.symbols:}") List<String> tradingSymbols,
                              @Value("${autostock.minute-archive.symbols:}") List<String> archiveSymbols) {
        this.repository = repository;
        this.marketData = marketData;
        this.clock = clock;
        this.configuredSymbols = validCodes(tradingSymbols, archiveSymbols);
    }

    /** 저장된 사전을 올리고 저장·조회 청취자를 건다 — 다른 빈이 표시를 시작하기 전(빈 초기화 때)에 끝낸다. */
    @PostConstruct
    public void start() {
        int loaded = loadSaved();
        StockNames.onLearn((code, name, source) -> unsaved.put(code, new Learned(name, source)));
        StockNames.onMiss(this::request);
        requestConfigured();
        log.info("종목명 사전 {}건 불러옴 — 조회 대기 {}건(설정 종목 {}개 중 이름 없는 것 + 확인한 지 {}일 지난 이름)", loaded,
                pending.size(), configuredSymbols.size(), REFRESH_AFTER.toDays());
    }

    /** 청취자를 풀고, 아직 남기지 못한 이름을 저장한다(best-effort). */
    @PreDestroy
    public void stop() {
        StockNames.onLearn(null);
        StockNames.onMiss(null);
        saveLearned();
    }

    /** 배운 이름을 저장하고 대기열을 조금씩 조회한다(클래스 설명 4·5). */
    @Scheduled(fixedDelay = 2_000, initialDelay = 5_000)
    public void lookupPending() {
        saveLearned();
        int looked = 0;
        for (String code : List.copyOf(pending.keySet())) {
            if (looked >= MAX_LOOKUPS_PER_RUN) {
                break;
            }
            if (waitingRetry(code)) {
                continue; // 실패 뒤 대기 중 — 대기열에 그대로 두었다가 시간이 되면 조회한다
            }
            Boolean force = pending.remove(code);
            if (force == null || (!force && StockNames.isKnown(code))) {
                continue; // 다른 실행이 가져갔거나, 그사이 다른 경로로 이름을 배웠다
            }
            looked++;
            lookup(code, force);
        }
        saveLearned();
    }

    /** 평일 08:10 KST — 7일 지난 이름을 다시 조회하고, 설정 종목의 빈 이름을 채운다. */
    @Scheduled(cron = "0 10 8 * * MON-FRI", zone = "Asia/Seoul")
    public void refreshStale() {
        failures.clear(); // 새 날 — 어제 그만둔 종목도 다시 해 본다
        Instant before = clock.instant().minus(REFRESH_AFTER);
        try {
            repository.findByUpdatedAtBefore(before).forEach(e -> request(e.getSymbol(), true));
        } catch (RuntimeException e) {
            log.warn("종목명 갱신 대상 조회 실패 — 다음 날 다시 확인: {}", e.getMessage());
        }
        requestConfigured();
    }

    /** 이름을 모르는 종목의 조회 요청({@link StockNames#onMiss}) — 같은 종목은 대기열에 한 번만 쌓인다. */
    void request(String code) {
        request(code, false);
    }

    private void request(String code, boolean force) {
        // 실패 뒤 대기 중이어도 대기열에는 넣는다 — 조회는 대기가 끝난 뒤에 한다(lookupPending)
        pending.merge(code, force, Boolean::logicalOr);
    }

    private boolean waitingRetry(String code) {
        Instant notBefore = retryNotBefore.get(code);
        return notBefore != null && clock.instant().isBefore(notBefore);
    }

    private void lookup(String code, boolean force) {
        String name;
        try {
            name = marketData.stockQuote(code).name();
        } catch (RuntimeException e) {
            retryLater(code, force, e.getMessage());
            return;
        }
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > StockNames.MAX_NAME_LENGTH) {
            retryLater(code, force, trimmed.isEmpty() ? "ka10001 응답에 종목명 없음"
                    : "ka10001 종목명이 " + StockNames.MAX_NAME_LENGTH + "자를 넘음");
            return;
        }
        retryNotBefore.remove(code);
        failures.remove(code);
        StockNames.learn(code, trimmed, StockNames.Source.KIWOOM);
        // 이름이 그대로여서 사전이 바뀌지 않았어도 확인 시각은 남긴다(7일 갱신 기준)
        unsaved.put(code, new Learned(trimmed, StockNames.Source.KIWOOM));
    }

    /**
     * 실패한 종목을 {@link #RETRY_AFTER_FAILURE} 뒤에 다시 조회하게 대기열에 남긴다. {@link #MAX_ATTEMPTS}번 연달아 실패하면 그만둔다.
     * 이름을 이미 아는 종목(7일 갱신)은 표시에 지장이 없어 INFO, 모르는 종목은 WARN.
     */
    private void retryLater(String code, boolean force, String reason) {
        retryNotBefore.put(code, clock.instant().plus(RETRY_AFTER_FAILURE));
        int attempt = failures.merge(code, 1, Integer::sum);
        boolean giveUp = attempt >= MAX_ATTEMPTS;
        if (giveUp) {
            failures.remove(code);
        } else {
            pending.merge(code, force, Boolean::logicalOr);
        }
        String next = giveUp ? "%d번 실패 — 다음 요청(표시·평일 08:10 갱신)까지 둔다".formatted(attempt)
                : "%d분 뒤 다시 조회(%d/%d)".formatted(RETRY_AFTER_FAILURE.toMinutes(), attempt, MAX_ATTEMPTS);
        if (StockNames.isKnown(code)) {
            log.info("종목명 갱신 조회 실패({}) — 저장된 이름을 그대로 쓴다, {}: {}", StockNames.label(code), next, reason);
        } else {
            // 모르는 종목은 StockNames.label을 부르지 않는다 — 표기가 조회 요청(onMiss)을 다시 넣어 "그만둔다"가 거짓이 된다
            log.warn("종목명 조회 실패({}) — {}: {}", code, next, reason);
        }
    }

    /** 모아 둔 이름을 DB에 남긴다 — 실패한 이름은 버린다(메모리 사전은 그대로 쓰고, 다음 기동에 다시 조회된다). */
    void saveLearned() {
        for (String code : List.copyOf(unsaved.keySet())) {
            Learned learned = unsaved.remove(code);
            if (learned == null) {
                continue;
            }
            try {
                repository.upsert(code, learned.name(), learned.source().name(), clock.instant());
            } catch (RuntimeException e) {
                log.warn("종목명 저장 실패({}) — 이번 실행은 메모리 사전만 쓴다: {}",
                        StockNames.label(code), e.getMessage());
            }
        }
    }

    private int loadSaved() {
        Instant staleBefore = clock.instant().minus(REFRESH_AFTER);
        int loaded = 0;
        try {
            for (StockNameEntity row : repository.findAll()) {
                StockNames.preload(row.getSymbol(), row.getName(), sourceOf(row.getSource()));
                loaded++;
                if (row.getUpdatedAt().isBefore(staleBefore)) {
                    request(row.getSymbol(), true);
                }
            }
        } catch (RuntimeException e) {
            log.warn("종목명 사전 불러오기 실패 — 키움 조회로만 채운다: {}", e.getMessage());
        }
        return loaded;
    }

    private void requestConfigured() {
        configuredSymbols.stream().filter(code -> !StockNames.isKnown(code)).forEach(this::request);
    }

    private static StockNames.Source sourceOf(String value) {
        try {
            return StockNames.Source.valueOf(value);
        } catch (RuntimeException e) {
            return StockNames.Source.DART; // 모르는 출처는 가장 낮은 우선순위로 — 키움 조회가 덮는다
        }
    }

    private static List<String> validCodes(List<String> first, List<String> second) {
        Set<String> codes = new LinkedHashSet<>();
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        for (String raw : all) {
            String code = raw == null ? "" : raw.trim();
            if (code.matches(StockCode.PATTERN)) {
                codes.add(code);
            }
        }
        return List.copyOf(codes);
    }
}

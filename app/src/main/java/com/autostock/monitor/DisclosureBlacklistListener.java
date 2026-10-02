package com.autostock.monitor;

import com.autostock.common.event.DisclosureBlacklisted;
import com.autostock.common.util.StockCode;
import com.autostock.common.util.StockNames;
import com.autostock.portfolio.PositionBook;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 공시 블랙리스트 신규 등록({@link DisclosureBlacklisted})을 Notifier(텔레그램)로 이어주는
 * 브리지 (PLAN.md ADR-14, 트랙 G1) — {@link IpoAlertListener}와 동일 패턴. risk 모듈은 발송
 * 수단을 전혀 모르고 이벤트만 발행하며, 이 리스너가 실제 채널로 이어준다.
 *
 * <p><b>요약 발송(2026-10-01, aiDoc/alert-digest.md)</b>: 전날 접수된 공시가 아침 수집(08:35·기동 따라잡기)에서
 * 한꺼번에 들어온다(10/1 기동 때 29건이 0.4초에, 매매 대상·보유 해당 0건). 건별로 보내면 텔레그램 발송 제한
 * (같은 채팅 초당 1건 권장, 초과 시 429)에 걸려 유실되고 긴급 알림이 그 사이에 묻힌다. 그래서
 * <ul>
 *   <li>매매 대상({@code strategy.c3.symbols})이거나 보유 중인 종목 — 지금처럼 바로 WARN 1건</li>
 *   <li>그 밖의 종목 — 모아 두었다가 1분마다 요약 INFO 1건(매수 금지 자체는 risk가 이미 적용했다)</li>
 * </ul>
 * 매매 대상 목록은 strategy 모듈 타입이 아니라 설정값({@code @Value})으로 읽는다 — strategy가 이미 monitor를
 * 참조하므로 반대 방향 타입 의존은 순환이 된다(package-info 참고).
 */
@Component
public class DisclosureBlacklistListener {

    /** 요약에 이름을 싣는 종목 수 상한 — 넘으면 "외 N종목"(텔레그램 메시지 4096자 제한 여유). */
    static final int DIGEST_MAX_SYMBOLS = 30;

    private final Notifier notifier;
    private final PositionBook positionBook;
    private final Set<String> tradingSymbols;
    /** 요약 대기 중인 등록 — 이벤트는 기동 스레드·스케줄 스레드 어디서든 올 수 있어 동기화한다. */
    private final List<DisclosureBlacklisted> pending = new ArrayList<>();

    public DisclosureBlacklistListener(Notifier notifier,
                                       PositionBook positionBook,
                                       @Value("${strategy.c3.symbols:}") List<String> tradingSymbols) {
        this.notifier = notifier;
        this.positionBook = positionBook;
        this.tradingSymbols = tradingSymbols.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 매수 금지 신규 등록 — 매매 대상·보유 종목이면 즉시 WARN, 아니면 요약 대기열에 넣는다. */
    @EventListener
    public void onDisclosureBlacklisted(DisclosureBlacklisted event) {
        String relevance = relevance(event.symbol());
        if (relevance != null) {
            notifier.notify(NoticeLevel.WARN,
                    "매수 금지 등록(%s): %s — %s(rcept_no=%s), 해제예정 %s".formatted(
                            relevance, StockNames.label(event.symbol(), event.corpName()), event.disclosureType(),
                            event.rceptNo(), event.expiresOn()));
            return;
        }
        synchronized (pending) {
            pending.add(event);
        }
    }

    /** 1분마다 대기열을 요약 1건으로 보낸다. 대기열이 비어 있으면 아무것도 보내지 않는다. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void flushDigest() {
        List<DisclosureBlacklisted> batch;
        synchronized (pending) {
            if (pending.isEmpty()) {
                return;
            }
            batch = List.copyOf(pending);
            pending.clear();
        }
        notifier.notify(NoticeLevel.INFO, digest(batch));
    }

    /** 종료 직전 남은 대기열도 보낸다(best-effort — Notifier가 실패를 삼킨다). */
    @PreDestroy
    public void flushOnShutdown() {
        flushDigest();
    }

    private String relevance(String symbol) {
        boolean held = holds(symbol);
        boolean traded = tradingSymbols.contains(symbol);
        if (held && traded) {
            return "보유·매매 대상";
        }
        if (held) {
            return "보유 종목";
        }
        return traded ? "매매 대상" : null;
    }

    private boolean holds(String symbol) {
        try {
            return positionBook.holds(new StockCode(symbol));
        } catch (IllegalArgumentException e) {
            return false; // 종목코드 형식이 아니면 보유일 수 없다
        }
    }

    /**
     * 요약 본문 — 건수·종목 수, 유형별 건수, 종목 이름(최대 {@value #DIGEST_MAX_SYMBOLS}개), 해제예정일 범위.
     * 예: "공시 블랙리스트 신규 29건(24종목, 매매 대상·보유 종목 아님 — 매수 금지만 적용)".
     */
    static String digest(List<DisclosureBlacklisted> batch) {
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, Long> types = new LinkedHashMap<>();
        for (DisclosureBlacklisted e : batch) {
            names.putIfAbsent(e.symbol(), e.corpName());
            types.merge(e.disclosureType(), 1L, Long::sum);
        }
        String typeText = types.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(t -> t.getKey() + " " + t.getValue())
                .collect(Collectors.joining(", "));
        String symbolText = names.entrySet().stream()
                .limit(DIGEST_MAX_SYMBOLS)
                .map(n -> StockNames.label(n.getKey(), n.getValue()))
                .collect(Collectors.joining(", "));
        if (names.size() > DIGEST_MAX_SYMBOLS) {
            symbolText += " 외 " + (names.size() - DIGEST_MAX_SYMBOLS) + "종목";
        }
        LocalDate firstExpiry = batch.stream().map(DisclosureBlacklisted::expiresOn)
                .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        LocalDate lastExpiry = batch.stream().map(DisclosureBlacklisted::expiresOn)
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        String expiryText = firstExpiry == null || firstExpiry.equals(lastExpiry)
                ? String.valueOf(firstExpiry)
                : firstExpiry + "~" + lastExpiry;
        return "공시 블랙리스트 신규 %d건(%d종목, 매매 대상·보유 종목 아님 — 매수 금지만 적용)\n유형: %s\n종목: %s\n해제예정: %s"
                .formatted(batch.size(), names.size(), typeText, symbolText, expiryText);
    }
}

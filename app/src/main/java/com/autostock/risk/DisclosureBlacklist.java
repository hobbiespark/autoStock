package com.autostock.risk;

import com.autostock.common.event.DisclosureBlacklisted;
import com.autostock.common.event.DisclosureRisk;
import com.autostock.common.util.MarketConstants;
import com.autostock.macrointel.DisclosureType;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DART 공시 기반 매수 배제 목록 (PLAN.md ADR-14, 트랙 G1 — {@code macrointel.
 * DisclosureBlacklistSyncScheduler}가 발행하는 {@link DisclosureRisk} 이벤트를 구독해 유상증자·
 * CB·BW·EB 발행 결정이 난 종목을 자동으로 등록한다).
 *
 * <p>이 클래스가 risk 모듈에 있는 이유는 {@link MacroGuard}와 동일하다 — "한도·차단 판단은
 * risk 소유" 원칙(risk/package-info.java), macrointel은 수집·발행만 담당한다. macrointel →
 * risk 방향 타입 의존은 전혀 없다 — {@link DisclosureRisk}는 common 이벤트이고, 이 클래스가
 * {@code @EventListener}로 구독하는 것뿐이다({@code MacroGuard.onMacroIndicator}와 같은 패턴).
 *
 * <h2>두 가지 등록 경로</h2>
 * <ul>
 *   <li><b>공시 자동 등록</b>({@link #onDisclosureRisk}) — 만료일이 있다(등록 기간 지나면
 *       자동 해제, {@link DisclosureBlacklistExpiryScheduler}). (symbol, rceptNo) 조합이 이미
 *       등록돼 있으면(스케줄러 재처리) 중복 저장·중복 알림 없이 조용히 무시한다(멱등).</li>
 *   <li><b>운영자 수동 add/remove</b> — 기존 골격 그대로 유지한다. 만료일이 없다(운영자가
 *       명시적으로 remove()할 때까지 무기한) — DB에는 남기지 않고 메모리에만 둔다(운영자의
 *       임시 조치는 재시작 시 사라지는 것이 오히려 안전하다 — 의도치 않게 오래 남는 차단을
 *       막기 위함). remove()는 이 종목의 공시 자동 등록 이력도 함께 지운다(재시작해도 되살아
 *       나지 않도록 — "리스크 해소 확인 후 해제"라는 원래 의도를 완전한 해제로 존중한다).</li>
 * </ul>
 *
 * <p>스레드 안전: 조회(RiskGate의 매수 판단 경로)와 갱신(이벤트 리스너, 운영자 add/remove,
 * 만료 스케줄러)이 서로 다른 스레드에서 동시에 일어날 수 있어 {@link ConcurrentHashMap}을 쓴다.
 */
@Component
public class DisclosureBlacklist {

    private static final Logger log = LoggerFactory.getLogger(DisclosureBlacklist.class);

    private final DisclosureBlacklistRepository repository;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    /** 운영자 수동 add() — 무기한, DB 미영속(클래스 설명 참고). */
    private final Set<String> manualBlacklist = ConcurrentHashMap.newKeySet();

    /** 공시 자동 등록 — symbol별 "현재 유효한 것 중 가장 늦은" 만료일(DB 캐시, 조회 성능용). */
    private final Map<String, LocalDate> disclosureExpiryBySymbol = new ConcurrentHashMap<>();

    public DisclosureBlacklist(DisclosureBlacklistRepository repository,
                                ApplicationEventPublisher publisher, Clock clock) {
        this.repository = repository;
        this.publisher = publisher;
        this.clock = clock;
    }

    @PostConstruct
    void loadActiveFromDb() {
        reloadDisclosureCache();
    }

    /** 이 종목이 지금 매수 배제 대상인지 — 수동 등록 또는 만료되지 않은 공시 자동 등록. */
    public boolean isBlacklisted(String symbol) {
        if (manualBlacklist.contains(symbol)) {
            return true;
        }
        LocalDate expiresOn = disclosureExpiryBySymbol.get(symbol);
        return expiresOn != null && !today().isAfter(expiresOn);
    }

    /** 배제 목록에 종목을 추가한다(운영자 수동 등록 — 무기한, 클래스 설명 참고). */
    public void add(String symbol) {
        manualBlacklist.add(symbol);
    }

    /** 배제 목록에서 종목을 제거한다(운영자 수동 해제 — 공시 자동 등록 이력도 함께 지운다). */
    public void remove(String symbol) {
        manualBlacklist.remove(symbol);
        disclosureExpiryBySymbol.remove(symbol);
        repository.deleteBySymbol(symbol);
    }

    /** 지금 공시 사유로 등록돼 있는 종목 수(수동 등록 포함) — 대시보드 카운트용(DashboardFacade). */
    public int activeCount() {
        Set<String> union = new HashSet<>(manualBlacklist);
        union.addAll(disclosureExpiryBySymbol.keySet());
        return union.size();
    }

    /**
     * macrointel.DisclosureBlacklistSyncScheduler가 발행하는 공시 리스크 이벤트를 받아
     * 등록한다. (symbol, rceptNo) 조합이 이미 있으면 멱등 처리(중복 저장·중복 알림 없음).
     */
    @EventListener
    public void onDisclosureRisk(DisclosureRisk event) {
        if (repository.existsBySymbolAndRceptNo(event.symbol(), event.rceptNo())) {
            log.debug("이미 등록된 공시 — 스킵(멱등): symbol={}, rceptNo={}", event.symbol(), event.rceptNo());
            return;
        }
        repository.save(new DisclosureBlacklistEntity(
                event.symbol(), event.corpName(), event.disclosureType(),
                event.rceptNo(), event.rceptDt(), event.expiresOn()));
        mergeExpiry(event.symbol(), event.expiresOn());

        log.warn("공시 블랙리스트 신규 등록: symbol={}, type={}, rceptNo={}, 만료={}",
                event.symbol(), event.disclosureType(), event.rceptNo(), event.expiresOn());
        publisher.publishEvent(new DisclosureBlacklisted(
                event.symbol(), event.corpName(), labelOf(event.disclosureType()),
                event.rceptNo(), event.expiresOn(), Instant.now()));
    }

    /** 만료된 공시 자동 등록을 DB·메모리에서 정리한다 — {@link DisclosureBlacklistExpiryScheduler}가 매일 호출. */
    void releaseExpired() {
        LocalDate today = today();
        long deleted = repository.deleteByExpiresOnBefore(today);
        if (deleted > 0) {
            log.info("공시 블랙리스트 만료 해제: {}건(만료 기준일={})", deleted, today);
        }
        reloadDisclosureCache();
    }

    private void reloadDisclosureCache() {
        LocalDate today = today();
        Map<String, LocalDate> fresh = new ConcurrentHashMap<>();
        for (DisclosureBlacklistEntity entity : repository.findByExpiresOnGreaterThanEqual(today)) {
            fresh.merge(entity.getSymbol(), entity.getExpiresOn(),
                    (a, b) -> a.isAfter(b) ? a : b);
        }
        disclosureExpiryBySymbol.clear();
        disclosureExpiryBySymbol.putAll(fresh);
    }

    private void mergeExpiry(String symbol, LocalDate expiresOn) {
        disclosureExpiryBySymbol.merge(symbol, expiresOn, (a, b) -> a.isAfter(b) ? a : b);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(MarketConstants.KST));
    }

    private static String labelOf(String disclosureTypeName) {
        try {
            return DisclosureType.valueOf(disclosureTypeName).label();
        } catch (RuntimeException e) {
            return disclosureTypeName; // 알 수 없는 값이 와도(스키마 진화 등) 원문 그대로 노출
        }
    }
}

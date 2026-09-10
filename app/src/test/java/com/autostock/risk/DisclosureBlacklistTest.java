package com.autostock.risk;

import com.autostock.common.event.DisclosureBlacklisted;
import com.autostock.common.event.DisclosureRisk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DisclosureBlacklist 영속화·멱등·만료 해제 검증 (PLAN.md ADR-14, 트랙 G1). RiskGate 차단
 * 경로 자체는 RiskGateMacroTest가 이미 커버한다(add/remove 골격 무변경) — 이 테스트는 새로 추가된
 * 이벤트 리스너(onDisclosureRisk)·만료 해제(releaseExpired) 로직에 집중한다.
 */
class DisclosureBlacklistTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(LocalDate.of(2026, 9, 11).atStartOfDay().toInstant(ZoneOffset.of("+09:00")), ZoneOffset.UTC);

    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher publisher = published::add;
    private DisclosureBlacklistRepository repository;
    private DisclosureBlacklist blacklist;

    @BeforeEach
    void setUp() {
        repository = mock(DisclosureBlacklistRepository.class);
        blacklist = new DisclosureBlacklist(repository, publisher, FIXED_CLOCK);
    }

    private DisclosureRisk riskEvent(String symbol, String rceptNo, LocalDate expiresOn) {
        return new DisclosureRisk(symbol, "테스트종목", "CONVERTIBLE_BOND", rceptNo,
                LocalDate.of(2026, 9, 10), expiresOn, Instant.now());
    }

    @Test
    void 공시_이벤트를_받으면_저장하고_등록_알림을_발행한다() {
        blacklist.onDisclosureRisk(riskEvent("005930", "r1", LocalDate.of(2027, 3, 9)));

        verify(repository, times(1)).save(any(DisclosureBlacklistEntity.class));
        assertEquals(1, published.size());
        DisclosureBlacklisted notice = (DisclosureBlacklisted) published.get(0);
        assertEquals("005930", notice.symbol());
        assertEquals("r1", notice.rceptNo());
        assertEquals("전환사채(CB) 발행 결정", notice.disclosureType());
    }

    @Test
    void 같은_symbol_rceptNo_조합이_이미_있으면_중복_저장도_중복_알림도_없다() {
        when(repository.existsBySymbolAndRceptNo("005930", "r1")).thenReturn(true);

        blacklist.onDisclosureRisk(riskEvent("005930", "r1", LocalDate.of(2027, 3, 9)));

        verify(repository, times(0)).save(any());
        assertTrue(published.isEmpty());
    }

    @Test
    void 등록된_종목은_만료일까지_isBlacklisted가_true다() {
        blacklist.onDisclosureRisk(riskEvent("005930", "r1", LocalDate.of(2027, 3, 9)));

        assertTrue(blacklist.isBlacklisted("005930"));
    }

    @Test
    void 만료일이_지나면_isBlacklisted가_false다() {
        // "오늘"(2026-09-11) 이전에 이미 만료된 건 — 등록 직후에도 차단하지 않아야 함.
        blacklist.onDisclosureRisk(riskEvent("005930", "r1", LocalDate.of(2026, 1, 1)));

        assertFalse(blacklist.isBlacklisted("005930"));
    }

    @Test
    void releaseExpired은_만료된_행을_DB에서_지우고_메모리_캐시를_다시_로드한다() {
        when(repository.deleteByExpiresOnBefore(LocalDate.of(2026, 9, 11))).thenReturn(1L);
        when(repository.findByExpiresOnGreaterThanEqual(LocalDate.of(2026, 9, 11))).thenReturn(List.of());

        blacklist.releaseExpired();

        verify(repository).deleteByExpiresOnBefore(LocalDate.of(2026, 9, 11));
        verify(repository, times(1)).findByExpiresOnGreaterThanEqual(LocalDate.of(2026, 9, 11));
    }

    @Test
    void 운영자_수동_add는_즉시_차단되고_remove는_DB_이력까지_지운다() {
        blacklist.add("000660");
        assertTrue(blacklist.isBlacklisted("000660"));

        blacklist.remove("000660");

        assertFalse(blacklist.isBlacklisted("000660"));
        verify(repository).deleteBySymbol("000660");
    }

    @Test
    void activeCount는_수동과_공시_등록을_합쳐_중복없이_센다() {
        blacklist.add("000660");
        blacklist.onDisclosureRisk(riskEvent("005930", "r1", LocalDate.of(2027, 3, 9)));
        blacklist.onDisclosureRisk(riskEvent("005930", "r2", LocalDate.of(2027, 6, 9))); // 같은 종목 추가 공시

        assertEquals(2, blacklist.activeCount());
    }
}

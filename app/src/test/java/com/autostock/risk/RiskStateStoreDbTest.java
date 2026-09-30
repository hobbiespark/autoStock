package com.autostock.risk;

import com.autostock.support.PostgresDataJpaTest;
import com.autostock.support.PostgresTestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 PostgreSQL에서 risk 상태 저장소 검증 — V9 스키마, 단일 행 갱신·버전, 일별 누계 갱신(Phase 0.2,
 * aiDoc/risk-state-persistence.md). {@code @DataJpaTest}는 {@code @Component}를 띄우지 않으므로 저장소를 직접
 * 조립한다(REQUIRES_NEW 프록시 없이 테스트 트랜잭션 안에서 돈다 — 쿼리·매핑 검증이 목적).
 */
class RiskStateStoreDbTest extends PostgresDataJpaTest {

    private static final Instant T1 = Instant.parse("2026-10-01T05:20:00Z");
    private static final Instant T2 = Instant.parse("2026-10-01T06:00:00Z");

    @Autowired
    RiskStateRepository stateRepository;

    @Autowired
    RiskDailyPnlRepository dailyPnlRepository;

    @Autowired
    JdbcTemplate jdbc;

    private RiskStateStore store;

    @BeforeEach
    void setUp() {
        store = new RiskStateStore(stateRepository, dailyPnlRepository);
    }

    private Map<String, Object> stateRow() {
        stateRepository.flush();
        return jdbc.queryForMap("select kill_switch_engaged, kill_switch_reason, changed_by, version from risk_state");
    }

    @Test
    void V9는_해제_상태의_단일_행으로_시작한다() {
        RiskStateEntity state = store.loadKillSwitch().orElseThrow();

        assertFalse(state.isKillSwitchEngaged(), PostgresTestDatabase.kind());
        assertEquals(1, jdbc.queryForObject("select count(*) from risk_state", Integer.class));
    }

    @Test
    void 작동과_해제는_단일_행을_갱신하고_버전을_올리며_해제_후에도_작동_사유를_남긴다() {
        store.saveEngaged("시세 단절 200초", T1);
        Map<String, Object> engaged = stateRow();
        assertEquals(true, engaged.get("kill_switch_engaged"));
        assertEquals("시세 단절 200초", engaged.get("kill_switch_reason"));
        assertNull(engaged.get("changed_by"));
        assertEquals(1L, ((Number) engaged.get("version")).longValue());

        store.saveReleased("dashboard", T2);
        Map<String, Object> released = stateRow();
        assertEquals(false, released.get("kill_switch_engaged"));
        assertEquals("시세 단절 200초", released.get("kill_switch_reason"));
        assertEquals("dashboard", released.get("changed_by"));
        assertEquals(2L, ((Number) released.get("version")).longValue());
        assertEquals(1, jdbc.queryForObject("select count(*) from risk_state", Integer.class));
    }

    @Test
    void 행이_지워져_있어도_저장하면_다시_만든다() {
        jdbc.update("delete from risk_state");

        store.saveEngaged("텔레그램 원격 명령", T1);

        assertEquals(true, stateRow().get("kill_switch_engaged"));
        assertTrue(store.loadKillSwitch().orElseThrow().isKillSwitchEngaged());
    }

    @Test
    void 두_번째_행은_CHECK_제약으로_거부된다() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into risk_state (id, kill_switch_engaged, changed_at) values (2, false, now())"));
    }

    @Test
    void 일별_누계는_날짜별_한_행으로_갱신되고_다른_날짜와_섞이지_않는다() {
        LocalDate day1 = LocalDate.of(2026, 10, 1);
        LocalDate day2 = LocalDate.of(2026, 10, 2);

        store.saveDailyPnl(day1, new BigDecimal("-1000.5"), T1);
        dailyPnlRepository.flush();
        store.saveDailyPnl(day1, new BigDecimal("-2500.25"), T2);
        store.saveDailyPnl(day2, new BigDecimal("300"), T2);
        dailyPnlRepository.flush();

        assertEquals(2, jdbc.queryForObject("select count(*) from risk_daily_pnl", Integer.class));
        assertEquals(0, new BigDecimal("-2500.25").compareTo(store.loadDailyPnl(day1).orElseThrow()));
        assertEquals(0, new BigDecimal("300").compareTo(store.loadDailyPnl(day2).orElseThrow()));
        assertTrue(store.loadDailyPnl(LocalDate.of(2026, 9, 30)).isEmpty());
        String raw = jdbc.queryForObject("select trade_date::text || '|' || realized_pnl::text from risk_daily_pnl where trade_date = ?",
                String.class, day1);
        assertEquals("2026-10-01|-2500.2500", raw);
    }
}

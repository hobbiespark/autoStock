package com.autostock.ipo;

import com.autostock.support.PostgresDataJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 PostgreSQL에서 공모주 딜 낙관적 잠금(R2, V8 {@code ipo_deals.version}) 검증 — 주문은 2026-09-30
 * {@code OrderRepositoryDbTest}로 검증했고 IPO 딜만 "미검증"으로 남아 있었다(aiDoc/order-concurrency.md 6절).
 *
 * <p>재현하는 경합: {@link IpoSyncScheduler}가 전체 딜을 읽어 상태·권고를 다시 계산하는 사이에
 * {@link IpoDealCommandService}(수동 지표 입력)가 같은 딜을 먼저 저장한다. 배치의 나중 저장은 거부돼야 하고
 * (배치는 {@link OptimisticLockingFailureException}을 잡아 건너뛴다), 수동 입력은 덮이지 않아야 한다.
 * 반대로 수동 입력이 나중이면 같은 예외를 {@code monitor.ApiExceptionHandler}가 409로 바꿔 다시 입력하게 한다.
 */
class IpoDealRepositoryDbTest extends PostgresDataJpaTest {

    @Autowired
    IpoDealRepository deals;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    private static IpoDealEntity newDeal(String rceptNo) {
        return new IpoDealEntity("01359815", "테스트공모", rceptNo, "DART", Instant.now());
    }

    private long version(String rceptNo) {
        Long v = jdbc.queryForObject("select version from ipo_deals where rcept_no = ?", Long.class, rceptNo);
        return v == null ? -1 : v;
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)   // 커밋마다 버전을 보려면 트랜잭션을 나눠야 한다
    void 저장할_때마다_버전이_오르고_접수번호로_조회된다() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        String rcept = "20261001900001";
        try {
            Long id = tx.execute(s -> deals.saveAndFlush(newDeal(rcept)).getId());
            assertEquals(0L, version(rcept));

            tx.executeWithoutResult(s -> {
                IpoDealEntity deal = deals.findById(id).orElseThrow();
                deal.updateStatus(IpoStatus.SUBSCRIBING, Instant.now());
                deals.saveAndFlush(deal);
            });

            assertEquals(1L, version(rcept));
            assertTrue(deals.findByRceptNo(rcept).isPresent());
        } finally {
            jdbc.update("delete from ipo_deals where rcept_no = ?", rcept);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)   // 트랜잭션을 나눠야 동시 갱신을 재현할 수 있다
    void 배치_재계산과_수동_입력이_겹치면_배치의_나중_저장이_거부되고_수동_입력이_남는다() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        String rcept = "20261001900002";
        try {
            Long id = tx.execute(s -> deals.saveAndFlush(newDeal(rcept)).getId());
            IpoDealEntity batchCopy = tx.execute(s -> deals.findById(id).orElseThrow());   // 배치가 읽은 사본
            IpoDealEntity manualCopy = tx.execute(s -> deals.findById(id).orElseThrow());  // 수동 입력이 읽은 사본

            manualCopy.applyMetrics(new BigDecimal("812.50"), new BigDecimal("0.2150"), Instant.now());
            tx.executeWithoutResult(s -> deals.saveAndFlush(manualCopy));                   // 수동 입력이 먼저 저장

            batchCopy.applyRecommendation(IpoRecommendation.SKIP, "배치 재계산(테스트)", Instant.now());
            OptimisticLockingFailureException rejected = assertThrows(OptimisticLockingFailureException.class,
                    () -> tx.executeWithoutResult(s -> deals.saveAndFlush(batchCopy)));
            // IpoSyncScheduler의 catch와 ApiExceptionHandler(409)가 받는 OptimisticLockingFailureException의 하위 타입으로 번역된다
            assertInstanceOf(ObjectOptimisticLockingFailureException.class, rejected);

            Map<String, Object> row = jdbc.queryForMap(
                    "select institutional_competition_rate, lockup_commit_rate, recommendation, recommend_reason, version"
                            + " from ipo_deals where rcept_no = ?", rcept);
            assertEquals(0, new BigDecimal("812.50").compareTo((BigDecimal) row.get("institutional_competition_rate")));
            assertEquals(0, new BigDecimal("0.2150").compareTo((BigDecimal) row.get("lockup_commit_rate")));
            assertEquals("PENDING", row.get("recommendation"), "배치의 권고 변경은 반영되지 않아야 한다");
            assertEquals("지표 미확보 — [발행조건확정] 수요예측 결과 공시 전(공시되면 자동 입력, 수동 입력도 가능)",
                    row.get("recommend_reason"), "딜을 만들 때의 사유 그대로여야 한다");
            assertEquals(1L, ((Number) row.get("version")).longValue());
        } finally {
            jdbc.update("delete from ipo_deals where rcept_no = ?", rcept);
        }
    }

    @Test
    void V11_공모_종류와_지표_출처를_저장하고_회사별_미판정_딜을_찾는다() {
        IpoDealEntity rights = new IpoDealEntity("01359815", "한울반도체", "20261002900001", "DART", Instant.now());
        rights.classifyOffering(OfferingKind.RIGHTS, Instant.now());
        IpoDealEntity unknown = new IpoDealEntity("01359815", "한울반도체", "20261002900002", "DART", Instant.now());
        IpoDealEntity ipo = new IpoDealEntity("01801026", "브릴스", "20261002900003", "DART", Instant.now());
        ipo.classifyOffering(OfferingKind.IPO, Instant.now());
        ipo.applyDemandForecast(new BigDecimal("1187.74"), new BigDecimal("0.2175"), "20260916000234", Instant.now());
        deals.saveAllAndFlush(java.util.List.of(rights, unknown, ipo));

        assertEquals(2, deals.findByCorpCode("01359815").size());
        assertEquals(java.util.List.of("20261002900002"), deals.findByOfferingKindIsNull().stream()
                .map(IpoDealEntity::getRceptNo).filter(r -> r.startsWith("20261002900")).toList()); // 다른 시험의 행은 빼고 본다
        Map<String, Object> row = jdbc.queryForMap(
                "select offering_kind, metrics_source, metrics_rcept_no, institutional_competition_rate, lockup_commit_rate"
                        + " from ipo_deals where rcept_no = '20261002900003'");
        assertEquals("IPO", row.get("offering_kind"));
        assertEquals("DART", row.get("metrics_source"));
        assertEquals("20260916000234", row.get("metrics_rcept_no"));
        assertEquals(0, new BigDecimal("1187.74").compareTo((BigDecimal) row.get("institutional_competition_rate")));
        assertEquals(0, new BigDecimal("0.2175").compareTo((BigDecimal) row.get("lockup_commit_rate")));
    }
}

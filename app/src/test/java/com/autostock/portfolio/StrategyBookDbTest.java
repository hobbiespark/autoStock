package com.autostock.portfolio;

import com.autostock.common.event.Fill;
import com.autostock.common.event.Side;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.ClientOrderId;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import com.autostock.common.util.StockCode;
import com.autostock.support.PostgresDataJpaTest;
import com.autostock.support.PostgresTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 전략별 장부를 실제 PostgreSQL(V20)에 저장·복원한다 — 운영처럼 트랜잭션 밖에서(체결 이벤트 스레드) 부른다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StrategyBookDbTest extends PostgresDataJpaTest {

    private static final Instant NOW = Instant.parse("2026-10-19T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Autowired
    StrategyPositionRepository positions;

    @Autowired
    StrategyFillRepository fills;

    @Autowired
    JdbcTemplate jdbc;

    private int seq;

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from strategy_positions");
        jdbc.update("delete from strategy_fills");
    }

    private Fill fill(String strategyId, Side side, long qty, String price) {
        String id = ClientOrderId.generate(LocalDate.of(2026, 10, 19), strategyId, "005930", side, ++seq).value();
        return new Fill(id, new BrokerOrderId("B" + seq), new StockCode("005930"), side, new Quantity(qty),
                new Price(new BigDecimal(price)), NOW);
    }

    @Test
    void 매수_매도가_표에_남고_재기동하면_보유를_읽는다() {
        StrategyBook book = new StrategyBook(positions, fills, CLOCK, 0.00015, 0.0020);
        book.onFill(fill("VB-DAY", Side.BUY, 10, "100000"));
        book.onFill(fill("VB-DAY", Side.BUY, 10, "102000"));
        book.onFill(fill("ON-CLOSE", Side.BUY, 5, "101000"));
        book.onFill(fill("VB-DAY", Side.SELL, 20, "103000"));

        assertEquals(1, jdbc.queryForObject("select count(*) from strategy_positions", Integer.class),
                PostgresTestDatabase.kind());
        assertEquals(4, jdbc.queryForObject("select count(*) from strategy_fills", Integer.class));
        BigDecimal realized = jdbc.queryForObject(
                "select realized_pnl from strategy_fills where side = 'SELL'", BigDecimal.class);
        assertEquals(0, new BigDecimal("35268.00").compareTo(realized));

        StrategyBook restarted = new StrategyBook(positions, fills, CLOCK, 0.00015, 0.0020);
        restarted.loadOnStartup();
        assertEquals(5, restarted.position("ON-CLOSE", new StockCode("005930")).orElseThrow().quantity());
        assertEquals(0, restarted.openPositionCount("VB-DAY"));

        List<StrategyBook.DaySummary> day = restarted.daySummaries(LocalDate.of(2026, 10, 19));
        StrategyBook.DaySummary vb = day.stream().filter(s -> s.strategyId().equals("VB-DAY")).findFirst().orElseThrow();
        assertEquals(2, vb.buyFills());
        assertEquals(1, vb.sellFills());
        assertEquals(1, vb.wins());
    }
}

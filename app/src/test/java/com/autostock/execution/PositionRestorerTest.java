package com.autostock.execution;

import com.autostock.common.event.PositionRestored;
import com.autostock.common.util.StockCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PositionRestorerTest {

    private final BrokerPort brokerPort = mock(BrokerPort.class);
    private final List<Object> published = new ArrayList<>();

    private BrokerBalance balanceWith(List<Map<String, Object>> holdings) {
        return new BrokerBalance(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, holdings);
    }

    @Test
    void 보유_원소마다_A_접두를_벗긴_종목코드로_복원_이벤트를_발행한다() {
        // 실측 형식(2026-09-11): stk_cd="A005930", rmnd_qty=보유수량, pur_pric=평단
        when(brokerPort.balance()).thenReturn(balanceWith(List.of(
                Map.of("stk_cd", "A005930", "rmnd_qty", "000000000000019", "pur_pric", "000000259974"))));

        new PositionRestorer(brokerPort, published::add, "LIVE").restore();

        assertEquals(List.of(new PositionRestored(new StockCode("005930"), 19, new BigDecimal("259974"))),
                published);
    }

    @Test
    void 종목코드_형식이_틀린_원소는_건너뛰고_나머지는_복원한다() {
        when(brokerPort.balance()).thenReturn(balanceWith(List.of(
                Map.of("stk_cd", "A5930", "rmnd_qty", "10", "pur_pric", "1000"),
                Map.of("stk_cd", "A000660", "rmnd_qty", "3", "pur_pric", "200000"))));

        new PositionRestorer(brokerPort, published::add, "LIVE").restore();

        assertEquals(1, published.size());
        assertEquals(new StockCode("000660"), ((PositionRestored) published.get(0)).symbol());
    }

    @Test
    void SIM_모드에서는_잔고를_조회하지_않는다() {
        new PositionRestorer(brokerPort, published::add, "SIM").restore();

        assertEquals(List.of(), published);
    }
}

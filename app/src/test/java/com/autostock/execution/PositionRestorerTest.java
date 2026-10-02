package com.autostock.execution;

import com.autostock.common.event.PositionRestored;
import com.autostock.common.util.Price;
import com.autostock.common.util.StockCode;
import com.autostock.common.util.StockNames;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PositionRestorerTest {

    private final BrokerPort brokerPort = mock(BrokerPort.class);
    private final List<Object> published = new ArrayList<>();

    private BrokerBalance balanceWith(List<BrokerHolding> holdings) {
        return new BrokerBalance(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, holdings);
    }

    @Test
    void 보유마다_복원_이벤트를_발행하고_종목명을_남긴다() {
        // 원소 해석(A 접두·후보 키·형식 오류 제외)은 어댑터 몫이다 — KiwoomBrokerAdapterTest(실행 계획 1.5)
        when(brokerPort.balance()).thenReturn(balanceWith(List.of(
                new BrokerHolding(new StockCode("005930"), "삼성전자", 19, new Price(new BigDecimal("259974"))),
                new BrokerHolding(new StockCode("000660"), "", 3, new Price(new BigDecimal("200000"))))));

        new PositionRestorer(brokerPort, published::add, "LIVE").restore();

        assertEquals(List.of(new PositionRestored(new StockCode("005930"), 19, new Price(new BigDecimal("259974"))),
                new PositionRestored(new StockCode("000660"), 3, new Price(new BigDecimal("200000")))), published);
        // 잔고의 종목명(stk_nm)을 사전에 남겨 복원 로그·알림·화면이 바로 "삼성전자(005930)"로 표시한다
        assertEquals("삼성전자(005930)", StockNames.label("005930"));
    }

    @Test
    void 평단_미상이면_null로_복원한다() {
        // 예전에는 0을 넣었고, 그 값이 C3 강제 청산 지정가로 쓰이면 0원 주문이 나갔다(조각 16·19)
        when(brokerPort.balance()).thenReturn(balanceWith(List.of(
                new BrokerHolding(new StockCode("005930"), "", 19, null))));

        new PositionRestorer(brokerPort, published::add, "LIVE").restore();

        assertEquals(List.of(new PositionRestored(new StockCode("005930"), 19, null)), published);
    }

    @Test
    void SIM_모드에서는_잔고를_조회하지_않는다() {
        new PositionRestorer(brokerPort, published::add, "SIM").restore();

        assertEquals(List.of(), published);
    }
}

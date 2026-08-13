package com.autostock.risk;

import com.autostock.common.event.Fill;
import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Side;
import com.autostock.common.event.Signal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskGateTest {

    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher publisher = published::add;

    private RiskProperties properties;
    private KillSwitch killSwitch;
    private PositionBook positionBook;
    private RiskGate gate;

    @BeforeEach
    void setUp() {
        properties = new RiskProperties(0.10, 5, -0.03, 0.05, -0.02, 30, 10_000_000);
        killSwitch = new KillSwitch();
        positionBook = new PositionBook();
        gate = new RiskGate(publisher, killSwitch, properties,
                new PositionSizer(properties), positionBook, new DailyLimitTracker(properties));
    }

    private Signal buySignal(String symbol, String price) {
        return new Signal("test-strategy", symbol, Side.BUY, new BigDecimal(price), 1.0, Instant.now());
    }

    @Test
    void 고정비율_사이징으로_매수_주문_발행() {
        gate.onSignal(buySignal("005930", "70000"));

        assertEquals(1, published.size());
        OrderRequest order = (OrderRequest) published.get(0);
        // 1,000만원 * 10% = 100만원 / 7만원 = 14주
        assertEquals(14, order.quantity());
        assertEquals(Side.BUY, order.side());
    }

    @Test
    void 킬스위치_작동시_주문_차단() {
        killSwitch.engage("테스트");
        gate.onSignal(buySignal("005930", "70000"));
        assertTrue(published.isEmpty());
    }

    @Test
    void 보유_종목_추가_매수_차단() {
        positionBook.onFill(new Fill("k1", "b1", "005930", Side.BUY, 10,
                new BigDecimal("70000"), Instant.now()));
        gate.onSignal(buySignal("005930", "70000"));
        assertTrue(published.isEmpty());
    }

    @Test
    void 미보유_종목_매도_시그널_무시() {
        gate.onSignal(new Signal("test-strategy", "005930", Side.SELL,
                new BigDecimal("70000"), 1.0, Instant.now()));
        assertTrue(published.isEmpty());
    }

    @Test
    void 보유_종목_매도는_전량_청산() {
        positionBook.onFill(new Fill("k1", "b1", "005930", Side.BUY, 14,
                new BigDecimal("70000"), Instant.now()));
        gate.onSignal(new Signal("test-strategy", "005930", Side.SELL,
                new BigDecimal("71000"), 1.0, Instant.now()));

        assertEquals(1, published.size());
        assertEquals(14, ((OrderRequest) published.get(0)).quantity());
    }

    @Test
    void confidence가_0_5면_매수_수량이_절반이다() {
        gate.onSignal(new Signal("test-strategy", "005930", Side.BUY, new BigDecimal("70000"), 0.5, Instant.now()));

        assertEquals(1, published.size());
        OrderRequest order = (OrderRequest) published.get(0);
        // 1,000만원 * 10% * 0.5 = 50만원 / 7만원 = 7.14... → 7주 (confidence=1.0일 때 14주의 절반)
        assertEquals(7, order.quantity());
    }

    @Test
    void confidence가_1_초과면_1_0으로_클램프돼_원래_수량과_같다() {
        gate.onSignal(new Signal("test-strategy", "005930", Side.BUY, new BigDecimal("70000"), 1.5, Instant.now()));

        assertEquals(1, published.size());
        assertEquals(14, ((OrderRequest) published.get(0)).quantity());
    }

    @Test
    void confidence가_0이하면_1_0으로_클램프돼_원래_수량과_같다() {
        gate.onSignal(new Signal("test-strategy", "005930", Side.BUY, new BigDecimal("70000"), 0.0, Instant.now()));

        assertEquals(1, published.size());
        assertEquals(14, ((OrderRequest) published.get(0)).quantity());
    }

    @Test
    void 동시_보유_한도_도달시_신규_매수_차단() {
        String[] symbols = {"A", "B", "C", "D", "E"};
        for (String s : symbols) {
            positionBook.onFill(new Fill("k" + s, "b" + s, s, Side.BUY, 1,
                    new BigDecimal("1000"), Instant.now()));
        }
        gate.onSignal(buySignal("005930", "70000"));
        assertTrue(published.isEmpty());
    }
}

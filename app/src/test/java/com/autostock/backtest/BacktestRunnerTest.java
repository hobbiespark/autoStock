package com.autostock.backtest;

import com.autostock.common.event.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BacktestRunner + TradeIntent 검증.
 *
 * <p>Phase 4에서 BacktestStrategy가 종가 시그널(Optional&lt;Side&gt;) 모델에서 장중 조건부
 * 주문(TradeIntent) 모델로 바뀌면서, 이 테스트도 새 타이밍 모델(오늘 시가로 판단 → 오늘
 * 장중 고가/저가로 체결 판정)에 맞춰 다시 작성했다. {@link BacktestRunner} 클래스 Javadoc의
 * 타이밍 모델/체결 순서 규약 설명을 함께 참고할 것.
 */
class BacktestRunnerTest {

    /** 첫 캔들 시가에서만 매수(시장가 성격의 buyStop)하고 이후에는 계속 보유하는 가장 단순한 전략. */
    private static final class BuyOnceThenHold implements BacktestStrategy {
        private boolean boughtYet = false;

        @Override
        public List<TradeIntent> onCandle(Candle today, PortfolioState state) {
            if (!boughtYet && !state.hasPosition()) {
                boughtYet = true;
                return List.of(TradeIntent.buyStop(today.open()));
            }
            return List.of();
        }
    }

    /** 첫 캔들 시가에서 매수 후, 보유 중이면 매번 "다음 시가 매도"를 신청하는 단기 보유 전략. */
    private static final class BuyOnceThenSellNextOpen implements BacktestStrategy {
        private boolean boughtYet = false;

        @Override
        public List<TradeIntent> onCandle(Candle today, PortfolioState state) {
            if (state.hasPosition()) {
                return List.of(TradeIntent.sellNextOpen());
            }
            if (!boughtYet) {
                boughtYet = true;
                return List.of(TradeIntent.buyStop(today.open()));
            }
            return List.of();
        }
    }

    /** 딱 한 번(첫 캔들에서만) 고정된 TradeIntent 목록을 반환하고, 그 뒤로는 아무것도 안 하는 테스트용 전략. */
    private static final class FixedIntentOnce implements BacktestStrategy {
        private final List<TradeIntent> firstDayIntents;
        private boolean called = false;

        FixedIntentOnce(List<TradeIntent> firstDayIntents) {
            this.firstDayIntents = firstDayIntents;
        }

        @Override
        public List<TradeIntent> onCandle(Candle today, PortfolioState state) {
            if (!called) {
                called = true;
                return firstDayIntents;
            }
            return List.of();
        }
    }

    private Candle candle(LocalDate date, String open, String high, String low, String close) {
        return new Candle("TEST", date, new BigDecimal(open), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(close), 1000L);
    }

    private List<Candle> uptrendCandles(int days) {
        List<Candle> candles = new ArrayList<>();
        LocalDate d0 = LocalDate.of(2026, 1, 2);
        BigDecimal open = new BigDecimal("10000");
        for (int i = 0; i < days; i++) {
            BigDecimal close = open.add(new BigDecimal("100"));
            candles.add(candle(d0.plusDays(i), open.toPlainString(),
                    close.add(new BigDecimal("50")).toPlainString(),
                    open.subtract(new BigDecimal("50")).toPlainString(),
                    close.toPlainString()));
            open = close;
        }
        return candles;
    }

    @Test
    void 매수_후_보유_전략은_상승장에서_양의_수익을_낸다() {
        BacktestRunner runner = new BacktestRunner(CostModel.defaults());
        BacktestResult result = runner.run(uptrendCandles(10), new BuyOnceThenHold(), new BigDecimal("10000000"));

        assertTrue(result.totalReturn() > 0, "상승장에서 매수 후 보유했으므로 총수익률은 양수여야 함");
        assertEquals(1, result.tradeCount(), "첫날 매수 1건만 체결되어야 함(이후 매도 없음)");
    }

    @Test
    void buyStop은_고가가_트리거에_못_미치면_체결되지_않는다() {
        List<Candle> candles = List.of(
                candle(LocalDate.of(2026, 1, 2), "10000", "10050", "9900", "10020"));
        // 트리거(10100)가 당일 고가(10050)보다 높으므로 도달하지 못한다.
        FixedIntentOnce strategy = new FixedIntentOnce(List.of(TradeIntent.buyStop(new BigDecimal("10100"))));

        BacktestRunner runner = new BacktestRunner(CostModel.defaults());
        BacktestResult result = runner.run(candles, strategy, new BigDecimal("1000000"));

        assertEquals(0, result.tradeCount(), "트리거 미도달이므로 체결이 없어야 함");
        assertEquals(0.0, result.totalReturn(), 1e-9, "체결이 없었으므로 자산 변화도 없어야 함");
    }

    @Test
    void buyStop은_갭상승_시가로_출발하면_트리거가_아니라_시가에_체결된다() {
        List<Candle> candles = List.of(
                candle(LocalDate.of(2026, 1, 2), "10000", "10200", "9950", "10100"));
        // 트리거(9800)가 시가(10000)보다 훨씬 낮다 — 이미 시가부터 트리거를 넘겨 출발했으므로
        // "그 가격에 살 수 있었던 적이 없다": 체결가는 트리거가 아니라 시가(10000)여야 한다.
        FixedIntentOnce strategy = new FixedIntentOnce(List.of(TradeIntent.buyStop(new BigDecimal("9800"))));

        BacktestRunner runner = new BacktestRunner(CostModel.defaults());
        BacktestResult result = runner.run(candles, strategy, new BigDecimal("1000000"));

        assertEquals(1, result.tradeCount(), "체결은 1건 발생해야 함");
        assertTrue(result.totalReturn() > 0, "시가(10000)에 사서 종가(10100)까지 상승했으므로 이익이어야 함");
        // 만약 버그로 트리거가(9800)에 체결됐다면 훨씬 싼 값에 대량 매수한 셈이 되어
        // 당일 상승폭(약 1%)과 비교할 수 없이 큰 총수익률이 나온다 — 그런 비정상적 수익률이
        // 아님을 확인해 "시가 체결"이라는 룩어헤드 방지 규칙이 지켜졌는지 회귀 검증한다.
        assertTrue(result.totalReturn() < 0.05, "시가 체결이라면 당일 상승폭 수준(5% 미만)의 수익률이어야 함");
    }

    @Test
    void 같은_날_buyStop과_sellStop이_동시에_걸리면_손절이_우선한다() {
        // 하루 변동폭이 커서 고가는 매수 트리거를, 저가는 손절가를 모두 건드리는 날.
        List<Candle> candles = List.of(
                candle(LocalDate.of(2026, 1, 2), "10000", "10200", "9700", "10150"));
        FixedIntentOnce strategy = new FixedIntentOnce(List.of(
                TradeIntent.buyStop(new BigDecimal("10100")),
                TradeIntent.sellStop(new BigDecimal("9800"))));

        BacktestRunner runner = new BacktestRunner(CostModel.defaults());
        BacktestResult result = runner.run(candles, strategy, new BigDecimal("1000000"));

        // 최악 가정 원칙: 진입(10100 부근) 직후 같은 날 손절(9800 부근)까지 갔다고 보므로
        // 매수+매도 2건이 모두 체결되고, 손절가가 매수가보다 낮으므로 손실이 나야 한다.
        assertEquals(2, result.tradeCount(), "매수와 손절 매도가 모두 같은 날 체결되어야 함");
        assertTrue(result.totalReturn() < 0, "손절이 우선 적용됐으므로 손실이어야 함");
    }

    @Test
    void sellNextOpen은_다음_판단_시점의_시가에_체결된다() {
        List<Candle> candles = List.of(
                candle(LocalDate.of(2026, 1, 2), "10000", "10050", "9950", "10010"), // 매수일
                candle(LocalDate.of(2026, 1, 3), "10500", "10600", "10400", "10550"), // 매도일 — 시가 10500
                candle(LocalDate.of(2026, 1, 4), "9000", "9100", "8900", "9050")      // 이후 무관한 날
        );
        BacktestRunner runner = new BacktestRunner(CostModel.defaults());
        BacktestResult result = runner.run(candles, new BuyOnceThenSellNextOpen(), new BigDecimal("1000000"));

        assertEquals(2, result.tradeCount(), "매수 1건 + 매도 1건, 총 2건만 체결되어야 함");
        // 매수(약 10000) 후 이튿날 시가(10500)에 팔았으므로 이익이어야 한다. 만약 버그로
        // 매도가 셋째날 시가(9000)에 체결됐다면 오히려 손실이 났을 것이므로, 부호로 구분된다.
        assertTrue(result.totalReturn() > 0, "이튿날 시가(10500)에 매도했으므로 이익이어야 함");
    }
}

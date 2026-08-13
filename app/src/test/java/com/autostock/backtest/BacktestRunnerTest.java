package com.autostock.backtest;

import com.autostock.common.event.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BacktestRunner 검증 — 합성 캔들(상승 추세)로 "첫 봉 매수 후 보유" 전략을 돌려
 * (1) 양의 수익이 나는지, (2) 룩어헤드 없이 익일 시가로 체결되는지 확인한다.
 */
class BacktestRunnerTest {

    /** 첫 캔들에서만 매수 신호를 내고 이후에는 계속 보유하는 가장 단순한 전략. */
    private static final class BuyFirstThenHold implements BacktestStrategy {
        private boolean signaled = false;

        @Override
        public Optional<Side> onCandle(Candle candle, PortfolioState state) {
            if (!signaled && !state.hasPosition()) {
                signaled = true;
                return Optional.of(Side.BUY);
            }
            return Optional.empty();
        }
    }

    private Candle candle(LocalDate date, String open, String high, String low, String close) {
        return new Candle("TEST", date, new BigDecimal(open), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(close), 1000L);
    }

    /**
     * 상승 추세 10봉. 단, 0번째 캔들은 일부러 "장중 급등"(시가 10000, 종가 11000)으로 만들고
     * 1번째 캔들은 시가를 다시 10000으로 갭다운시켰다 — 룩어헤드 버그(신호를 낸 그날 종가로
     * 즉시 체결)가 있다면 매수가가 11000이 되어 1일차(종가 10100)에 손실이 나야 하지만,
     * 올바른 구현(익일 시가 10000 체결)이라면 1일차에 이익이 나야 한다.
     */
    private List<Candle> uptrendCandlesWithLookaheadTrap() {
        List<Candle> candles = new ArrayList<>();
        LocalDate d0 = LocalDate.of(2026, 1, 2);
        candles.add(candle(d0, "10000", "11000", "10000", "11000"));           // 0: 장중 급등
        candles.add(candle(d0.plusDays(1), "10000", "10200", "9900", "10100")); // 1: 시가 갭다운
        BigDecimal open = new BigDecimal("10100");
        for (int i = 2; i < 10; i++) {
            BigDecimal close = open.add(new BigDecimal("100"));
            candles.add(candle(d0.plusDays(i), open.toPlainString(),
                    close.add(new BigDecimal("100")).toPlainString(),
                    open.subtract(new BigDecimal("50")).toPlainString(),
                    close.toPlainString()));
            open = close;
        }
        return candles;
    }

    @Test
    void 첫봉_매수_후_보유_전략은_양의_수익을_낸다() {
        BacktestRunner runner = new BacktestRunner(CostModel.defaults());
        BacktestResult result = runner.run(uptrendCandlesWithLookaheadTrap(), new BuyFirstThenHold(),
                new BigDecimal("10000000"));

        assertTrue(result.totalReturn() > 0, "상승장에서 매수 후 보유했으므로 총수익률은 양수여야 함");
        assertEquals(1, result.tradeCount(), "첫 봉 매수 1건만 체결되어야 함(이후 신호 없음)");
    }

    @Test
    void 룩어헤드_없이_익일_시가로_체결된다() {
        BacktestRunner runner = new BacktestRunner(CostModel.defaults());
        BacktestResult result = runner.run(uptrendCandlesWithLookaheadTrap(), new BuyFirstThenHold(),
                new BigDecimal("10000000"));

        List<Double> returns = result.dailyReturns();
        // 0일차: 아직 체결 전(신호만 발생) → 포지션이 없으므로 수익률 변화가 없어야 한다.
        assertEquals(0.0, returns.get(0), 1e-9);

        // 1일차: 올바르게 "1일차 시가(10000)"에 체결됐다면 1일차 종가(10100)까지 상승분만큼
        // 이익이 나서 양(+)의 수익률이어야 한다. 만약 룩어헤드 버그로 "0일차 종가(11000)"에
        // 체결됐다면 1일차 종가(10100)는 오히려 진입가보다 낮아 손실(음수)이 났을 것이다.
        assertTrue(returns.get(1) > 0, "익일 시가 체결이면 1일차 수익률은 양수여야 함(룩어헤드 회귀 테스트)");
    }
}

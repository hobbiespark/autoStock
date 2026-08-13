package com.autostock.backtest;

import com.autostock.common.event.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WalkForwardRunner 검증 — 시드 고정 합성 데이터(추세 + 노이즈)로 창 분할/파라미터
 * 선택/DSR 시도 횟수 연동/OOS 수익률 길이가 규약대로 계산되는지 확인한다.
 */
class WalkForwardRunnerTest {

    private static final int TRAIN_SIZE = 40;
    private static final int TEST_SIZE = 10;
    private static final int TOTAL_CANDLES = 100;

    /**
     * 상승 추세(일 평균 +0.05%) 위에 정규분포 노이즈(표준편차 1%)를 얹은 합성 종가로
     * 캔들을 만든다. 시드를 고정해 매 테스트 실행마다 동일한 데이터가 나오게 한다.
     */
    private List<Candle> syntheticTrendCandles(int n, long seed) {
        Random random = new Random(seed);
        List<Candle> candles = new ArrayList<>();
        LocalDate date = LocalDate.of(2024, 1, 1);
        BigDecimal price = new BigDecimal("10000");

        for (int i = 0; i < n; i++) {
            double dailyReturn = 0.0005 + random.nextGaussian() * 0.01;
            BigDecimal open = price;
            BigDecimal close = open.multiply(BigDecimal.valueOf(1 + dailyReturn))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal high = open.max(close).add(new BigDecimal("50"));
            BigDecimal low = open.min(close).subtract(new BigDecimal("50"));
            candles.add(new Candle("TEST", date, open, high, low, close, 1000L));
            price = close;
            date = date.plusDays(1);
        }
        return candles;
    }

    private int expectedWindowCount(int totalCandles, int trainSize, int testSize) {
        int windowCount = 0;
        int start = 0;
        while (start + trainSize + testSize <= totalCandles) {
            windowCount++;
            start += testSize;
        }
        return windowCount;
    }

    @Test
    void 창_분할_개수가_train_test_길이_규칙대로_나온다() {
        List<Candle> candles = syntheticTrendCandles(TOTAL_CANDLES, 42L);
        WalkForwardRunner runner = new WalkForwardRunner(CostModel.defaults());
        List<Double> kCandidates = List.of(0.3, 0.5, 0.7);

        WalkForwardResult result = runner.run(candles, kCandidates, TRAIN_SIZE, TEST_SIZE, new BigDecimal("10000000"));

        int expectedWindows = expectedWindowCount(TOTAL_CANDLES, TRAIN_SIZE, TEST_SIZE);
        assertEquals(expectedWindows, result.selectedParams().size(),
                "구간별 선택 파라미터 개수 = 창 개수여야 함");
        assertTrue(expectedWindows >= 5, "이 테스트 데이터 크기라면 창이 5개 이상 나와야 함(테스트 전제 확인)");
    }

    @Test
    void 구간별_선택된_k값은_후보_목록_안에서_나온다() {
        List<Candle> candles = syntheticTrendCandles(TOTAL_CANDLES, 42L);
        WalkForwardRunner runner = new WalkForwardRunner(CostModel.defaults());
        List<Double> kCandidates = List.of(0.3, 0.5, 0.7);

        WalkForwardResult result = runner.run(candles, kCandidates, TRAIN_SIZE, TEST_SIZE, new BigDecimal("10000000"));

        assertTrue(result.selectedParams().size() > 0, "선택된 파라미터 기록이 존재해야 함");
        for (double selected : result.selectedParams()) {
            assertTrue(kCandidates.contains(selected), "선택된 k는 반드시 후보 목록 중 하나여야 함: " + selected);
        }
    }

    @Test
    void trial_수는_후보수_곱하기_창_수와_같다() {
        List<Candle> candles = syntheticTrendCandles(TOTAL_CANDLES, 7L);
        WalkForwardRunner runner = new WalkForwardRunner(CostModel.defaults());
        List<Double> kCandidates = List.of(0.3, 0.5, 0.7, 1.0);

        WalkForwardResult result = runner.run(candles, kCandidates, TRAIN_SIZE, TEST_SIZE, new BigDecimal("10000000"));

        int expectedWindows = expectedWindowCount(TOTAL_CANDLES, TRAIN_SIZE, TEST_SIZE);
        assertEquals(kCandidates.size() * expectedWindows, result.trials(),
                "DSR trial 수는 (후보 수) x (창 수)여야 함");
    }

    @Test
    void OOS_수익률_시계열_길이는_test_구간_길이의_합과_같다() {
        List<Candle> candles = syntheticTrendCandles(TOTAL_CANDLES, 123L);
        WalkForwardRunner runner = new WalkForwardRunner(CostModel.defaults());
        List<Double> kCandidates = List.of(0.3, 0.5, 0.7);

        WalkForwardResult result = runner.run(candles, kCandidates, TRAIN_SIZE, TEST_SIZE, new BigDecimal("10000000"));

        int expectedWindows = expectedWindowCount(TOTAL_CANDLES, TRAIN_SIZE, TEST_SIZE);
        assertEquals(TEST_SIZE * expectedWindows, result.oosResult().dailyReturns().size(),
                "OOS 일별 수익률 길이 = test 구간 길이 x 창 수여야 함(각 창의 test 구간을 이어붙이므로)");
    }

    @Test
    void 후보가_비어있으면_예외를_던진다() {
        List<Candle> candles = syntheticTrendCandles(TOTAL_CANDLES, 1L);
        WalkForwardRunner runner = new WalkForwardRunner(CostModel.defaults());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> runner.run(candles, List.of(), TRAIN_SIZE, TEST_SIZE, new BigDecimal("10000000")));
    }
}

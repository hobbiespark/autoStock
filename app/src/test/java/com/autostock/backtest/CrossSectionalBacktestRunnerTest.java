package com.autostock.backtest;

import com.autostock.common.event.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 합성 데이터로 횡단면 엔진의 회계·룩어헤드·상장폐지 처리를 검증한다(실데이터 실험은 RealDataCrossSectionalExperimentTest).
 */
class CrossSectionalBacktestRunnerTest {

    private static final CostModel ZERO_COST = new CostModel(0, 0, 0, 0);

    /** 2024-01-01부터 평일만 n일 — 주말·휴일 개념은 캘린더로만 표현하면 충분하다. */
    private static List<LocalDate> weekdays(int n) {
        List<LocalDate> out = new ArrayList<>();
        LocalDate d = LocalDate.of(2024, 1, 1);
        while (out.size() < n) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                out.add(d);
            }
            d = d.plusDays(1);
        }
        return out;
    }

    /** 가격 경로 f(i)로 캔들 생성. 시가=종가(체결가 검증을 단순하게). */
    private static List<Candle> candles(String symbol, List<LocalDate> cal, int from, int to, DoubleUnaryOperator f) {
        List<Candle> out = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            BigDecimal p = BigDecimal.valueOf(f.applyAsDouble(i));
            out.add(new Candle(symbol, cal.get(i), p, p, p, p, 1_000_000L));
        }
        return out;
    }

    /** 테스트용 전략: 히스토리 마지막 종가 / 첫 종가 상위 n. */
    private static CrossSectionalStrategy topReturn(int n, int history) {
        return new CrossSectionalStrategy() {
            @Override public String label() { return "test top" + n; }
            @Override public int minHistoryDays() { return history; }
            @Override public List<String> select(Map<String, double[]> histories) {
                List<Map.Entry<String, Double>> scored = new ArrayList<>();
                histories.forEach((s, c) -> scored.add(Map.entry(s, c[c.length - 1] / c[0])));
                scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
                return scored.stream().limit(n).map(Map.Entry::getKey).toList();
            }
        };
    }

    private static CrossSectionalBacktestRunner.Config config(List<LocalDate> cal, int startIdx, CostModel cost) {
        return new CrossSectionalBacktestRunner.Config(cal.get(startIdx), cal.get(cal.size() - 1),
                10, 5, new BigDecimal("1000000"), cost, 1);
    }

    @Test
    void 상승종목을_골라_동일가중으로_보유하면_자산이_그_종목을_따라간다() {
        List<LocalDate> cal = weekdays(80);
        Map<String, List<Candle>> data = new LinkedHashMap<>();
        data.put("UP", candles("UP", cal, 0, 79, i -> 100 + i));          // 매일 +1
        data.put("FLAT", candles("FLAT", cal, 0, 79, i -> 100));
        data.put("DOWN", candles("DOWN", cal, 0, 79, i -> 200 - i));

        var r = new CrossSectionalBacktestRunner().run(data, cal, topReturn(1, 10), config(cal, 20, ZERO_COST));

        assertThat(r.dates()).hasSize(60);
        assertThat(r.dailyReturns()).hasSize(60);
        // 첫날(i=20) 시가 120에 전액 매수 → 마지막날(i=79) 종가 179: 비용 0이므로 정확히 179/120
        assertThat(r.result().finalEquity().doubleValue()).isCloseTo(1_000_000 * 179.0 / 120.0, within(1.0));
        assertThat(r.avgHoldings()).isCloseTo(1.0, within(1e-9));
        assertThat(r.delistingsLiquidated()).isZero();
        // 월말 형성은 1월말·2월말·3월말(마지막날 제외) — 캘린더 80일은 1~4월 중순까지
        assertThat(r.rebalances()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void 두_종목_선택이면_첫날_시가_기준_50대50이다() {
        List<LocalDate> cal = weekdays(40);
        Map<String, List<Candle>> data = new LinkedHashMap<>();
        data.put("A", candles("A", cal, 0, 39, i -> 100 + i));
        data.put("B", candles("B", cal, 0, 39, i -> 50 + i));   // 수익률상 B가 더 높음
        data.put("C", candles("C", cal, 0, 39, i -> 100));

        var r = new CrossSectionalBacktestRunner().run(data, cal, topReturn(2, 5), config(cal, 10, ZERO_COST));

        // 첫날 수익률 = 0.5×(A 종가/시가) + 0.5×(B 종가/시가) − 1, 시가=종가라 0
        assertThat(r.dailyReturns().get(0)).isCloseTo(0.0, within(1e-12));
        // 둘째 날(i=11): A 110→111(+0.91%), B 60→61(+1.67%) → 동일가중 평균 ≈ 1.29%
        double expected = 0.5 * (111.0 / 110.0 - 1) + 0.5 * (61.0 / 60.0 - 1);
        assertThat(r.dailyReturns().get(1)).isCloseTo(expected, within(1e-9));
    }

    @Test
    void 전략은_형성일_종가까지만_본다_룩어헤드_없음() {
        List<LocalDate> cal = weekdays(30);
        Map<String, List<Candle>> data = new LinkedHashMap<>();
        data.put("A", candles("A", cal, 0, 29, i -> 100 + i));
        List<double[]> seen = new ArrayList<>();
        CrossSectionalStrategy spy = new CrossSectionalStrategy() {
            @Override public String label() { return "spy"; }
            @Override public int minHistoryDays() { return 5; }
            @Override public List<String> select(Map<String, double[]> h) {
                seen.add(h.get("A"));
                return List.of("A");
            }
        };
        new CrossSectionalBacktestRunner().run(data, cal, spy, config(cal, 10, ZERO_COST));

        // 최초 형성은 start 전날(i=9): 히스토리 마지막 값은 109여야 하고(110이면 룩어헤드), 길이는 5
        assertThat(seen.get(0)).hasSize(5);
        assertThat(seen.get(0)[4]).isEqualTo(109.0);
    }

    @Test
    void 시계열이_끝난_보유종목은_마지막_종가에_청산된다() {
        List<LocalDate> cal = weekdays(40);
        Map<String, List<Candle>> data = new LinkedHashMap<>();
        data.put("GONE", candles("GONE", cal, 0, 24, i -> 300 + i));   // i=24 이후 데이터 없음 = 상장폐지
        data.put("STAY", candles("STAY", cal, 0, 39, i -> 100));

        var r = new CrossSectionalBacktestRunner().run(data, cal, topReturn(1, 5), config(cal, 10, ZERO_COST));

        assertThat(r.delistingsLiquidated()).isEqualTo(1);
        // 폐지 청산 후 현금 보유 → 이후 일간 수익률 0 (다음 월말 형성 전까지)
        assertThat(r.dailyReturns().get(16)).isCloseTo(0.0, within(1e-12));
        // 자산은 310(시가 i=10) → 324(마지막 종가 i=24) 만큼만 반영
        assertThat(r.result().finalEquity().doubleValue()).isGreaterThan(1_000_000 * 324.0 / 310.0 - 1);
    }

    @Test
    void 비용모델이_매수_매도에_적용된다() {
        List<LocalDate> cal = weekdays(40);
        Map<String, List<Candle>> data = new LinkedHashMap<>();
        data.put("A", candles("A", cal, 0, 39, i -> 100));
        CostModel cost = new CostModel(0.001, 0.001, 0.002, 0.001);

        var r = new CrossSectionalBacktestRunner().run(data, cal, topReturn(1, 5), config(cal, 10, cost));

        // 가격 불변 종목을 계속 들고 있으면 손실은 오직 비용뿐: 매수 슬리피지 0.1% + 수수료 0.1% 이상
        double loss = 1 - r.result().finalEquity().doubleValue() / 1_000_000;
        assertThat(loss).isBetween(0.0019, 0.01);
        assertThat(r.trades()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void 형성일_거래가_없는_종목은_유니버스에서_빠진다() {
        List<LocalDate> cal = weekdays(40);
        Map<String, List<Candle>> data = new LinkedHashMap<>();
        // HALT는 i=9(최초 형성일)에 캔들이 없다
        List<Candle> halt = new ArrayList<>(candles("HALT", cal, 0, 8, i -> 500 + i));
        halt.addAll(candles("HALT", cal, 10, 39, i -> 500 + i));
        data.put("HALT", halt);
        data.put("A", candles("A", cal, 0, 39, i -> 100 + i));
        List<Map<String, double[]>> seen = new ArrayList<>();
        CrossSectionalStrategy spy = new CrossSectionalStrategy() {
            @Override public String label() { return "spy"; }
            @Override public int minHistoryDays() { return 5; }
            @Override public List<String> select(Map<String, double[]> h) { seen.add(h); return List.of("A"); }
        };
        new CrossSectionalBacktestRunner().run(data, cal, spy, config(cal, 10, ZERO_COST));

        assertThat(seen.get(0)).containsOnlyKeys("A");
    }
}

package com.autostock.backtest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * X 계열 사전 선언 전략(docs/research/gate1_diagnosis_20260918.md 3절). 파라미터는 문헌 기본값으로 고정하며
 * 스윕하지 않는다 — 결과를 본 뒤의 수정은 새 trial 선언이 필요하다(트랙 H와 동일 원칙).
 */
public final class CrossSectionalStrategies {

    private CrossSectionalStrategies() {
    }

    /**
     * X1 — 12-1 횡단면 모멘텀 롱온리. 최근 1개월(21거래일)을 뺀 직전 11개월(252-21) 수익률 상위 N종목 동일가중.
     * Jegadeesh & Titman(1993)의 표준 정의. skip-month는 단기 반전 효과를 피하기 위한 문헌 관행.
     */
    public static CrossSectionalStrategy momentum12_1(int topN) {
        return new Momentum(252, 21, topN);
    }

    /**
     * X2 — 저변동성 롱온리. 직전 252거래일 일간 로그수익률 표준편차 하위 N종목 동일가중
     * (Baker, Bradley & Wurgler 2011의 저변동성 이상현상 — 국내 실증 다수).
     */
    public static CrossSectionalStrategy lowVolatility(int bottomN) {
        return new LowVolatility(252, bottomN);
    }

    /**
     * X0 — 진단 베이스라인(trial 아님, 후보 전략이 아니라 판정 기준의 성질을 재는 자): 유니버스 전체를 동일가중
     * 월별 리밸런싱. 선별을 전혀 하지 않으므로 "이 유니버스에서 동일가중으로 고를 수 있는 것의 평균"이다.
     * 이것이 KODEX200(시총가중)에 지면 X1b/X2b의 FAIL은 선별 규칙이 아니라 동일가중 구조·벤치마크 정의의
     * 문제로 확정된다(docs/research/gate1_diagnosis_20260918.md 후속, ADR-15 판단 근거).
     * minHistoryDays는 X1과 같은 253으로 맞춰 후보 집합을 X1b와 동일하게 둔다.
     */
    public static CrossSectionalStrategy equalWeightAll() {
        return new EqualWeightAll(253);
    }

    /**
     * X0r — 무작위 N종목 동일가중(결정적 시드). "규칙 없는 선별"의 분포를 보여주는 대조군 — X1b/X2b가 이 수준과
     * 구분되지 않으면 선별 규칙에 정보가 없다는 뜻. 형성 시점마다 후보 집합의 해시로 시드를 파생해 상태 없이
     * 결정적으로 재현된다.
     */
    public static CrossSectionalStrategy randomPick(int n, long seed) {
        return new RandomPick(253, n, seed);
    }

    record EqualWeightAll(int minHistory) implements CrossSectionalStrategy {

        @Override
        public String label() {
            return "X0 유니버스 전체 동일가중(진단)";
        }

        @Override
        public int minHistoryDays() {
            return minHistory;
        }

        @Override
        public List<String> select(Map<String, double[]> histories) {
            List<String> all = new ArrayList<>(histories.keySet());
            java.util.Collections.sort(all);
            return all;
        }
    }

    record RandomPick(int minHistory, int n, long seed) implements CrossSectionalStrategy {

        @Override
        public String label() {
            return "X0r 무작위 " + n + "종목 동일가중(진단, seed=" + seed + ")";
        }

        @Override
        public int minHistoryDays() {
            return minHistory;
        }

        @Override
        public List<String> select(Map<String, double[]> histories) {
            List<String> all = new ArrayList<>(histories.keySet());
            java.util.Collections.sort(all);
            java.util.Collections.shuffle(all, new java.util.Random(seed ^ all.hashCode()));
            return new ArrayList<>(all.subList(0, Math.min(n, all.size())));
        }
    }

    record Momentum(int lookback, int skip, int topN) implements CrossSectionalStrategy {

        @Override
        public String label() {
            return "X1 " + (lookback / 21) + "-" + (skip / 21) + " 모멘텀 top" + topN;
        }

        @Override
        public int minHistoryDays() {
            return lookback + 1;
        }

        @Override
        public List<String> select(Map<String, double[]> histories) {
            List<Scored> scored = new ArrayList<>();
            for (Map.Entry<String, double[]> e : histories.entrySet()) {
                double[] c = e.getValue();
                int end = c.length - 1 - skip;          // skip 개월 전 종가
                int start = c.length - 1 - lookback;     // lookback 개월 전 종가
                if (start < 0 || c[start] <= 0) {
                    continue;
                }
                scored.add(new Scored(e.getKey(), c[end] / c[start] - 1.0));
            }
            return top(scored, topN, Comparator.comparingDouble(Scored::score).reversed());
        }
    }

    record LowVolatility(int window, int bottomN) implements CrossSectionalStrategy {

        @Override
        public String label() {
            return "X2 저변동성 bottom" + bottomN;
        }

        @Override
        public int minHistoryDays() {
            return window + 1;
        }

        @Override
        public List<String> select(Map<String, double[]> histories) {
            List<Scored> scored = new ArrayList<>();
            for (Map.Entry<String, double[]> e : histories.entrySet()) {
                double[] c = e.getValue();
                int n = c.length;
                double sum = 0, sumSq = 0;
                int count = 0;
                for (int i = n - window; i < n; i++) {
                    if (c[i - 1] <= 0 || c[i] <= 0) {
                        continue;
                    }
                    double r = Math.log(c[i] / c[i - 1]);
                    sum += r;
                    sumSq += r * r;
                    count++;
                }
                if (count < window * 0.9) {
                    continue; // 결측이 많은 종목(거래정지 등)은 변동성 추정이 왜곡되므로 제외
                }
                double mean = sum / count;
                double var = sumSq / count - mean * mean;
                if (var <= 0) {
                    continue; // 가격이 사실상 고정된 종목(관리종목·정지) 제외
                }
                scored.add(new Scored(e.getKey(), Math.sqrt(var)));
            }
            return top(scored, bottomN, Comparator.comparingDouble(Scored::score));
        }
    }

    private record Scored(String symbol, double score) {
    }

    /** 정렬 후 상위 n개 — 동점은 종목코드 순으로 결정적(deterministic)이게 고정한다. */
    private static List<String> top(List<Scored> scored, int n, Comparator<Scored> order) {
        scored.sort(order.thenComparing(Scored::symbol));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, scored.size()); i++) {
            out.add(scored.get(i).symbol());
        }
        return out;
    }
}

package com.autostock.backtest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 트랙 F 사전 선언 전략(docs/research/adr15_preregistration_20261114.md 4장). 규칙은 실행 전에 고정했고 스윕하지
 * 않는다. 재무는 {@link FundamentalsTable}의 시점 규칙으로만 읽는다. 시가총액은 형성 맥락의 KRX 스냅샷이다.
 */
public final class FactorStrategies {

    private FactorStrategies() {
    }

    /** X3 — F-Score ≥ minScore 종목에서 X1과 같은 12-1 모멘텀 상위 topN(Piotroski 2000 + Jegadeesh·Titman 1993). */
    public static CrossSectionalStrategy fScoreMomentum(FundamentalsTable table, int minScore, int topN) {
        return new FScoreMomentum(table, minScore, CrossSectionalStrategies.momentum12_1(topN), topN);
    }

    /** F1 — 마법공식(Greenblatt 2005): EY 순위 + ROC 순위의 합이 작은 topN. 금융업 제외. */
    public static CrossSectionalStrategy magicFormula(FundamentalsTable table, int topN) {
        return new MagicFormula(table, topN);
    }

    /** F2 — 매출총이익/자산(Novy-Marx 2013) 상위 topN. 금융업 제외. */
    public static CrossSectionalStrategy grossProfitability(FundamentalsTable table, int topN) {
        return new GrossProfitability(table, topN);
    }

    /**
     * Z3 — 시총가중 품질 필터(트랙 Z 사전 선언 2장): 유니버스 전부를 시가총액 비중으로 들되, 금융업이 아닌 회사 중
     * F-Score ≤ maxExcludedScore인 종목만 뺀다. 재무가 없는 종목·금융업은 남긴다.
     */
    public static CrossSectionalStrategy capWeightedQualityFilter(FundamentalsTable table, int maxExcludedScore) {
        return new CapWeighted(table, maxExcludedScore, 61);
    }

    /** Z3₀ — 진단(trial 아님): 같은 유니버스를 필터 없이 시가총액 비중으로. */
    public static CrossSectionalStrategy capWeightedAll() {
        return new CapWeighted(null, -1, 61);
    }

    static final class CapWeighted extends ContextOnly {
        private final FundamentalsTable table;
        private final int maxExcludedScore;
        private final int minHistory;

        CapWeighted(FundamentalsTable table, int maxExcludedScore, int minHistory) {
            this.table = table;
            this.maxExcludedScore = maxExcludedScore;
            this.minHistory = minHistory;
        }

        @Override
        public String label() {
            return table == null ? "Z3₀ 시총가중 전체(진단)" : "Z3 시총가중 F-Score<=" + maxExcludedScore + " 제외";
        }

        @Override
        public int minHistoryDays() {
            return minHistory;
        }

        @Override
        public List<String> select(FormationContext context, Map<String, double[]> histories) {
            List<String> out = new ArrayList<>();
            for (String s : histories.keySet()) {
                if (table != null) {
                    FundamentalsTable.Fundamentals f = table.asOf(context.formationDate(), s);
                    if (f != null && !f.looksFinancial() && f.fScore() <= maxExcludedScore) {
                        continue;
                    }
                }
                Double cap = context.marketCaps().get(s);
                if (cap != null && cap > 0) {
                    out.add(s);
                }
            }
            out.sort(Comparator.naturalOrder());
            return out;
        }

        @Override
        public Map<String, Double> targetWeights(FormationContext context, List<String> selected) {
            Map<String, Double> w = new LinkedHashMap<>();
            for (String s : selected) {
                w.put(s, context.marketCaps().get(s));
            }
            return w;
        }
    }

    /** 맥락 없이 부르면 어느 회계연도를 쓸지 알 수 없으므로 막는다 — 엔진은 언제나 맥락과 함께 부른다. */
    private abstract static class ContextOnly implements CrossSectionalStrategy {
        @Override
        public final List<String> select(Map<String, double[]> histories) {
            throw new IllegalStateException(label() + ": 형성 맥락(형성일·시가총액) 없이 부를 수 없음");
        }
    }

    static final class FScoreMomentum extends ContextOnly {
        private final FundamentalsTable table;
        private final int minScore;
        private final CrossSectionalStrategy momentum;
        private final int topN;

        FScoreMomentum(FundamentalsTable table, int minScore, CrossSectionalStrategy momentum, int topN) {
            this.table = table;
            this.minScore = minScore;
            this.momentum = momentum;
            this.topN = topN;
        }

        @Override
        public String label() {
            return "X3 F-Score>=" + minScore + " x 12-1모멘텀 top" + topN;
        }

        @Override
        public int minHistoryDays() {
            return momentum.minHistoryDays();
        }

        @Override
        public List<String> select(FormationContext context, Map<String, double[]> histories) {
            Map<String, double[]> passed = new LinkedHashMap<>();
            for (Map.Entry<String, double[]> e : histories.entrySet()) {
                FundamentalsTable.Fundamentals f = table.asOf(context.formationDate(), e.getKey());
                if (f != null && f.fScore() >= minScore) {
                    passed.put(e.getKey(), e.getValue());
                }
            }
            return momentum.select(passed);
        }
    }

    static final class MagicFormula extends ContextOnly {
        private final FundamentalsTable table;
        private final int topN;

        MagicFormula(FundamentalsTable table, int topN) {
            this.table = table;
            this.topN = topN;
        }

        @Override
        public String label() {
            return "F1 마법공식 top" + topN;
        }

        @Override
        public int minHistoryDays() {
            return 253; // X1b와 같은 후보 집합
        }

        @Override
        public List<String> select(FormationContext context, Map<String, double[]> histories) {
            List<String> symbols = new ArrayList<>();
            Map<String, Double> ey = new HashMap<>();
            Map<String, Double> roc = new HashMap<>();
            for (String s : histories.keySet()) {
                FundamentalsTable.Fundamentals f = table.asOf(context.formationDate(), s);
                Double cap = context.marketCaps().get(s);
                if (f == null || cap == null || f.looksFinancial() || Double.isNaN(f.op())) {
                    continue;
                }
                double ev = cap + f.liab() - f.cash();
                double capitalEmployed = (f.ca() - f.cash() - f.cl()) + f.ppe();
                if (!(ev > 0) || !(capitalEmployed > 0)) {
                    continue; // 분모 0 이하·결측 제외(사전 선언 규칙)
                }
                symbols.add(s);
                ey.put(s, f.op() / ev);
                roc.put(s, f.op() / capitalEmployed);
            }
            Map<String, Integer> eyRank = rankDescending(symbols, ey);
            Map<String, Integer> rocRank = rankDescending(symbols, roc);
            symbols.sort(Comparator.<String>comparingInt(s -> eyRank.get(s) + rocRank.get(s))
                    .thenComparing(Comparator.naturalOrder()));
            return List.copyOf(symbols.subList(0, Math.min(topN, symbols.size())));
        }
    }

    static final class GrossProfitability extends ContextOnly {
        private final FundamentalsTable table;
        private final int topN;

        GrossProfitability(FundamentalsTable table, int topN) {
            this.table = table;
            this.topN = topN;
        }

        @Override
        public String label() {
            return "F2 GP/A top" + topN;
        }

        @Override
        public int minHistoryDays() {
            return 253;
        }

        @Override
        public List<String> select(FormationContext context, Map<String, double[]> histories) {
            List<String> symbols = new ArrayList<>();
            Map<String, Double> gpa = new HashMap<>();
            for (String s : histories.keySet()) {
                FundamentalsTable.Fundamentals f = table.asOf(context.formationDate(), s);
                if (f == null || f.looksFinancial() || Double.isNaN(f.gp()) || !(f.assets() > 0)) {
                    continue;
                }
                symbols.add(s);
                gpa.put(s, f.gp() / f.assets());
            }
            symbols.sort(Comparator.<String>comparingDouble(s -> -gpa.get(s)).thenComparing(Comparator.naturalOrder()));
            return List.copyOf(symbols.subList(0, Math.min(topN, symbols.size())));
        }
    }

    /** 값이 클수록 1위. 동점은 종목코드 순(결정적). */
    private static Map<String, Integer> rankDescending(List<String> symbols, Map<String, Double> value) {
        List<String> sorted = new ArrayList<>(symbols);
        sorted.sort(Comparator.<String>comparingDouble(s -> -value.get(s)).thenComparing(Comparator.naturalOrder()));
        Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            rank.put(sorted.get(i), i + 1);
        }
        return rank;
    }
}

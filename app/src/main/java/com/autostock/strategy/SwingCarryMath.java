package com.autostock.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 단타 → 스윙·중기 이월 판단(2026-10-30, aiDoc/split-buy-swing-carry-2026-10-30.md) — 순수 함수.
 *
 * <p>입력은 어제까지 <b>완료된</b> 일봉 종가(오름차순)·이월 뒤 완료된 봉 수와 그 전략의 평단이다(순수 계산 코어라 일봉 타입을
 * 모른다 — ArchitectureRulesTest). 순서대로 처음 맞는 하나를 돌려준다.
 * <ol>
 *   <li>종가 ≤ 평단 × (1 − hardStop) → 손절</li>
 *   <li>스윙 단계, 종가 ≥ 평단 × (1 + swingTakeProfit) → 스윙 익절</li>
 *   <li>중기 단계, 종가 &lt; SMA(midSmaDays) → 중기 추세 이탈</li>
 *   <li>단계별 보유 기간(이월일 뒤 완료된 봉 수) 만료 → 매도(0이면 무기한)</li>
 *   <li>1차만, 종가 &gt; SMA(trendSmaDays) → 2차</li>
 *   <li>2차까지, 종가 &gt; SMA(midSmaDays) 이고 SMA(trendSmaDays) &gt; SMA(midSmaDays) → 3차(중기 단계로)</li>
 *   <li>그 밖 → 유지</li>
 * </ol>
 */
public final class SwingCarryMath {

    private SwingCarryMath() {
    }

    /** 이월 단계 — 1·2차 보유는 스윙, 3차까지 사면 중기. */
    public enum Stage { SWING, MID }

    /** 판단 종류. */
    public enum Action { HOLD, ADD, SELL }

    /**
     * @param action  할 일
     * @param tranche ADD일 때 살 차수(2·3), 그 밖은 0
     * @param reason  한 줄 근거
     */
    public record Decision(Action action, int tranche, String reason) {
        static Decision hold(String reason) {
            return new Decision(Action.HOLD, 0, reason);
        }

        static Decision sell(String reason) {
            return new Decision(Action.SELL, 0, reason);
        }

        static Decision add(int tranche, String reason) {
            return new Decision(Action.ADD, tranche, reason);
        }
    }

    /** 판단 규칙 값. */
    public record Rules(double hardStop, double swingTakeProfit, int trendSmaDays, int midSmaDays, int swingMaxDays,
                        int midMaxDays) {
    }

    /**
     * @param stage     지금 단계
     * @param tranches  지금까지 산 차수(1~3)
     * @param avgPrice  그 전략의 평단
     * @param closes    어제까지 완료된 일봉 종가(오름차순)
     * @param heldDays  이월한 날 뒤 완료된 봉 수(보유 기간)
     */
    public static Decision decide(Stage stage, int tranches, BigDecimal avgPrice, List<BigDecimal> closes, long heldDays,
                                  Rules rules) {
        if (avgPrice == null || avgPrice.signum() <= 0) {
            return Decision.hold("평단 미상 — 판단 보류");
        }
        if (closes == null || closes.isEmpty()) {
            return Decision.hold("일봉 없음 — 판단 보류");
        }
        BigDecimal close = closes.get(closes.size() - 1);
        BigDecimal ret = close.divide(avgPrice, 6, RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
        String head = "종가 %s / 평단 %s(%s%%)".formatted(close.toPlainString(), avgPrice.setScale(0, RoundingMode.HALF_UP),
                ret.movePointRight(2).setScale(2, RoundingMode.HALF_UP).toPlainString());
        if (ret.doubleValue() <= -rules.hardStop()) {
            return Decision.sell("손절(평단 −" + pct(rules.hardStop()) + "%) — " + head);
        }
        if (stage == Stage.SWING && ret.doubleValue() >= rules.swingTakeProfit()) {
            return Decision.sell("스윙 익절(평단 +" + pct(rules.swingTakeProfit()) + "%) — " + head);
        }
        BigDecimal trendSma = sma(closes, rules.trendSmaDays());
        BigDecimal midSma = sma(closes, rules.midSmaDays());
        if (stage == Stage.MID && midSma != null && close.compareTo(midSma) < 0) {
            return Decision.sell("중기 추세 이탈(종가 < SMA" + rules.midSmaDays() + " " + round(midSma) + ") — " + head);
        }
        int maxDays = stage == Stage.SWING ? rules.swingMaxDays() : rules.midMaxDays();
        if (maxDays > 0 && heldDays >= maxDays) {
            return Decision.sell("보유 기간 만료(" + (stage == Stage.SWING ? "스윙 " : "중기 ") + heldDays + "거래일) — " + head);
        }
        if (tranches == 1 && trendSma != null && close.compareTo(trendSma) > 0) {
            return Decision.add(2, "2차 매수(종가 > SMA" + rules.trendSmaDays() + " " + round(trendSma) + ") — " + head);
        }
        if (tranches == 2 && midSma != null && trendSma != null && close.compareTo(midSma) > 0
                && trendSma.compareTo(midSma) > 0) {
            return Decision.add(3, "3차 매수 → 중기(종가 > SMA" + rules.midSmaDays() + " " + round(midSma) + ", SMA"
                    + rules.trendSmaDays() + " > SMA" + rules.midSmaDays() + ") — " + head);
        }
        return Decision.hold("유지(" + stage + " " + tranches + "차, " + heldDays + "거래일) — " + head);
    }

    /** 마지막 days봉 종가 평균. 봉이 모자라면 null. */
    static BigDecimal sma(List<BigDecimal> closes, int days) {
        if (days <= 0 || closes.size() < days) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = closes.size() - days; i < closes.size(); i++) {
            sum = sum.add(closes.get(i));
        }
        return sum.divide(BigDecimal.valueOf(days), 4, RoundingMode.HALF_UP);
    }

    /** 비용 후 수익인가 — 기준가 ≥ 평단 × (1 + 왕복 비용). 단타 청산 시점에 팔지(수익) 넘길지(아님)를 정한다. */
    public static boolean profitableAfterCost(BigDecimal price, BigDecimal avgPrice, double roundTripCost) {
        if (price == null || avgPrice == null || avgPrice.signum() <= 0) {
            return false;
        }
        return price.compareTo(avgPrice.multiply(BigDecimal.valueOf(1 + roundTripCost))) >= 0;
    }

    private static String round(BigDecimal v) {
        return v.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private static String pct(double v) {
        return BigDecimal.valueOf(v).movePointRight(2).stripTrailingZeros().toPlainString();
    }
}

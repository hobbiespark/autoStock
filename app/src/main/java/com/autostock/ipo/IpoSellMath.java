package com.autostock.ipo;

import com.autostock.risk.KrxTickSize;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 공모주 상장일 실거래 매도 계산(2026-11-05 사용자 확정, aiDoc/ipo-automation.md 8절) — 순수 계산.
 *
 * <ul>
 *   <li>3분할 50%·30%·20% — 소수점(내림으로 남는 주)은 1차에 몬다. 나눌 수 없는 1~2주는 1차에 전량</li>
 *   <li>1차 지정가 = 공모가 × 60%(상장일 시초가 하한)를 호가 단위로 <b>올림</b> — 장전 동시호가에 내면 시초가에 체결된다</li>
 *   <li>2차·3차: 장중 고점 대비 −5%·−10%가 되면 지정가 = 체결가 × 0.99를 호가 단위로 내림, 단 하한 이상</li>
 * </ul>
 */
final class IpoSellMath {

    /** 차수별 비율(%) — 1차·2차·3차. */
    static final int[] PERCENTS = {50, 30, 20};
    /** 상장일 시초가 하한 — 공모가의 60%. */
    static final BigDecimal FLOOR_RATIO = new BigDecimal("0.60");
    /** 2차 — 장중 고점 대비 이만큼 내리면. */
    static final BigDecimal SECOND_DROP = new BigDecimal("0.05");
    /** 3차 — 장중 고점 대비 이만큼 내리면. */
    static final BigDecimal THIRD_DROP = new BigDecimal("0.10");
    /** 장중 매도 지정가 = 체결가 × 이 값. */
    static final BigDecimal INTRADAY_LIMIT_RATIO = new BigDecimal("0.99");

    private IpoSellMath() {
    }

    /** 수량을 50·30·20%로 나눈다. 내림으로 남는 주는 1차에 더한다. 0 이하면 {0,0,0}. */
    static int[] split(int quantity) {
        if (quantity <= 0) {
            return new int[]{0, 0, 0};
        }
        int second = quantity * PERCENTS[1] / 100;
        int third = quantity * PERCENTS[2] / 100;
        return new int[]{quantity - second - third, second, third};
    }

    /** 1차 지정가(시초가 하한) — 공모가 × 60%를 호가 단위로 올림. */
    static BigDecimal floorPrice(BigDecimal offerPrice) {
        return ceilToTick(offerPrice.multiply(FLOOR_RATIO));
    }

    /** 장중 매도 지정가 — 체결가 × 0.99 내림, 하한보다 낮으면 하한. */
    static BigDecimal intradayLimit(BigDecimal tradePrice, BigDecimal floor) {
        BigDecimal limit = floorToTick(tradePrice.multiply(INTRADAY_LIMIT_RATIO));
        return limit.compareTo(floor) < 0 ? floor : limit;
    }

    /** 고점 대비 drop(예: 0.05) 이상 내렸는가 — price ≤ high × (1 − drop). */
    static boolean droppedFromHigh(BigDecimal high, BigDecimal price, BigDecimal drop) {
        if (high == null || price == null || high.signum() <= 0) {
            return false;
        }
        return price.compareTo(high.multiply(BigDecimal.ONE.subtract(drop))) <= 0;
    }

    /** 호가 단위로 올림(눈금 경계를 넘으면 새 눈금으로 다시 맞춘다). */
    static BigDecimal ceilToTick(BigDecimal price) {
        return toTick(price, RoundingMode.CEILING);
    }

    /** 호가 단위로 내림. */
    static BigDecimal floorToTick(BigDecimal price) {
        return toTick(price, RoundingMode.FLOOR);
    }

    private static BigDecimal toTick(BigDecimal price, RoundingMode mode) {
        BigDecimal value = price;
        for (int i = 0; i < 3; i++) {
            BigDecimal tick = KrxTickSize.tickFor(value);
            BigDecimal aligned = value.divide(tick, 0, mode).multiply(tick).setScale(0, RoundingMode.UNNECESSARY);
            if (KrxTickSize.isAligned(aligned)) {
                return aligned;
            }
            value = aligned;
        }
        return KrxTickSize.align(value);
    }
}

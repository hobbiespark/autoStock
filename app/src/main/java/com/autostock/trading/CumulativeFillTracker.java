package com.autostock.trading;

import com.autostock.common.util.BrokerOrderId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 누적 체결 통보 → 이번 증분의 체결단가. 키움 체결 통보의 FID 910은 <b>누적 평균가</b>라(실측 2026-09-11, 19주가 3회 분할
 * 체결), 증분 단가는 (이번 누적금액 − 직전 누적금액) / 증분 수량으로 되돌린다. 직전 누적금액은 주문별로 기억한다.
 * {@link OrderNoticeHandler}에서 추출(B3 B안, aiDoc/large-classes.md) — 계산 경계를 핸들러 없이 테스트하기 위해서다.
 *
 * <p>{@link #increment}는 계산만 하고 상태를 바꾸지 않는다. 핸들러가 낙관적 잠금 충돌 때 같은 통보를 다시 계산하므로
 * (R2), 기억은 저장이 성공한 뒤 {@link #record}로 한 번만 한다.
 */
final class CumulativeFillTracker {

    private static final Logger log = LoggerFactory.getLogger(CumulativeFillTracker.class);

    /** 추적 맵 상한 — 비정상 누수 방어(일 주문 상한 30의 넉넉한 배수). */
    static final int MAX_TRACKED = 500;

    /** brokerOrderId → 직전 누적 체결금액(수량×평균가). 재시작 시 유실 — 평균가 근사로 폴백. */
    private final Map<BrokerOrderId, BigDecimal> cumulativeNotional = new ConcurrentHashMap<>();

    /**
     * 이번 통보의 증분 단가와 새 누적금액. 호출부가 증분 수량(누적 − 직전)이 양수임을 먼저 확인한다.
     *
     * @param averagePrice  통보의 누적 평균가(FID 910). 없으면 둘 다 null
     * @param cumulativeQty 통보의 누적 체결량(FID 911)
     * @param previousQty   이 통보 전까지 반영된 누적 체결량(DB)
     */
    Increment increment(BrokerOrderId brokerOrderId, BigDecimal averagePrice, long cumulativeQty, long previousQty) {
        if (averagePrice == null) {
            return Increment.UNKNOWN;
        }
        BigDecimal cumulative = averagePrice.multiply(BigDecimal.valueOf(cumulativeQty));
        if (previousQty == 0) {
            // 첫 체결: 누적 = 증분이므로 평균가가 곧 증분 단가 — 원 스케일 그대로
            return new Increment(averagePrice, cumulative);
        }
        BigDecimal previous = cumulativeNotional.get(brokerOrderId);
        if (previous == null) {
            // 재시작 등으로 직전 누적금액 유실 — 이번 평균가로 근사(오차 미미, 로그로 명시)
            log.info("직전 누적금액 미보유 — 평균가 근사로 증분 단가 계산: {}", brokerOrderId);
            previous = averagePrice.multiply(BigDecimal.valueOf(previousQty));
        }
        BigDecimal deltaPrice = cumulative.subtract(previous)
                .divide(BigDecimal.valueOf(cumulativeQty - previousQty), MathContext.DECIMAL64)
                .setScale(4, RoundingMode.HALF_UP);
        return new Increment(deltaPrice, cumulative);
    }

    /**
     * 저장이 성공한 뒤 이번 누적금액을 기억한다. 주문이 끝났으면(잔량 0·FILLED) 잊는다.
     *
     * @param cumulative  {@link Increment#cumulativeNotional()} — null이면(평균가 없음) 기억할 것이 없다
     * @param orderClosed 주문이 더 체결될 수 없는가
     */
    void record(BrokerOrderId brokerOrderId, BigDecimal cumulative, boolean orderClosed) {
        if (orderClosed) {
            cumulativeNotional.remove(brokerOrderId);
            return;
        }
        if (cumulative == null) {
            return;
        }
        if (cumulativeNotional.size() >= MAX_TRACKED && !cumulativeNotional.containsKey(brokerOrderId)) {
            log.warn("누적금액 추적 맵 상한({}) 도달 — 비움(단가는 평균가 폴백)", MAX_TRACKED);
            cumulativeNotional.clear();
        }
        cumulativeNotional.put(brokerOrderId, cumulative);
    }

    /**
     * @param deltaPrice         이번 증분의 체결단가. 평균가가 없으면 null(핸들러가 지정가로 근사 — 조각 17)
     * @param cumulativeNotional 이번 누적금액. 평균가가 없으면 null
     */
    record Increment(BigDecimal deltaPrice, BigDecimal cumulativeNotional) {
        static final Increment UNKNOWN = new Increment(null, null);
    }
}

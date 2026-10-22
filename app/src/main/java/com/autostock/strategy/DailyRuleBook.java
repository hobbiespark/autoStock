package com.autostock.strategy;

import com.autostock.common.event.Candle;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 일봉 규칙 묶음 — 백테스트 규칙 클래스를 라이브에서 그대로 쓰기 위한 포트(2026-10-16, 차트 기법 5종 —
 * aiDoc/multi-strategy-paper-2026-10-16.md 3-3).
 *
 * <p>규칙 클래스(일목·볼린저·슈퍼트렌드·RSI2·돌파 리테스트)는 {@code backtest} 모듈에 있고, backtest는 strategy의
 * 순수 함수({@link BreakoutMath} 등)를 쓴다. strategy가 backtest를 직접 참조하면 모듈 순환이 생기므로, 이 인터페이스를
 * strategy에 두고 backtest가 구현한다(구현: {@code backtest.TaRuleBook}). 런타임에 스프링이 이어 준다.
 *
 * <p>구현 계약 — "백테스트=라이브 동형":
 * <ol>
 *   <li>완료된 봉을 규칙에 순서대로 넣고, 백테스트 러너와 같은 체결 규칙으로 "그림자 포지션"을 굴린다.</li>
 *   <li>어제 종가 시점의 그림자 상태로 오늘 시가 봉을 넣어 오늘 할 일(주문 의사)을 받는다.</li>
 * </ol>
 */
public interface DailyRuleBook {

    /** 규칙 ID 목록(예: TA-H1 … TA-H5) — 전략 ID로도 쓴다. */
    List<String> ruleIds();

    /** 사람이 읽는 이름(예: "일목균형표"). */
    String ruleName(String ruleId);

    /**
     * @param completed 오늘 이전의 완료된 일봉(날짜 오름차순)
     * @param today     오늘(KST)
     * @param todayOpen 오늘 시가
     */
    RulePlan plan(String ruleId, String symbol, List<Candle> completed, LocalDate today, BigDecimal todayOpen);

    /**
     * 오늘의 계획.
     *
     * @param shadowHolding  어제 종가 시점에 규칙상 보유 중인가(그림자 포지션)
     * @param shadowAvgPrice 그림자 평단(미보유면 null)
     * @param orders         오늘 할 일 — 비어 있으면 아무것도 하지 않는다
     */
    record RulePlan(boolean shadowHolding, BigDecimal shadowAvgPrice, List<RuleOrder> orders) {
        public RulePlan {
            orders = orders == null ? List.of() : List.copyOf(orders);
        }

        public boolean has(RuleOrder.Kind kind) {
            return orders.stream().anyMatch(o -> o.kind() == kind);
        }

        public RuleOrder first(RuleOrder.Kind kind) {
            return orders.stream().filter(o -> o.kind() == kind).findFirst().orElse(null);
        }
    }

    /**
     * 주문 의사.
     *
     * @param price 장중 트리거 가격(BUY_STOP·SELL_STOP). 시가 주문은 null
     */
    record RuleOrder(Kind kind, BigDecimal price) {

        public enum Kind {
            /** 시가(개장 직후) 매수 — 백테스트의 buyAtOpen(트리거 ≤ 시가). */
            BUY_AT_OPEN,
            /** 장중 체결가가 price 이상이면 매수. */
            BUY_STOP,
            /** 시가(개장 직후) 전량 매도 — 백테스트의 sellNextOpen. */
            SELL_AT_OPEN,
            /** 장중 체결가가 price 이하이면 전량 매도(손절). */
            SELL_STOP
        }
    }
}

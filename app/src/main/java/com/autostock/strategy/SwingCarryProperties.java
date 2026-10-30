package com.autostock.strategy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Locale;

/**
 * 단타 → 스윙·중기 이월과 3분할 매수(2026-10-30, aiDoc/split-buy-swing-carry-2026-10-30.md) — {@code strategy.carry.*}.
 * 값은 사전 선언이다. 결과를 본 뒤 바꾸면 새 trial.
 *
 * @param enabled         이월 켜기. 켜진 전략은 1차 분할(종목 예산의 1/3)로 사고, 끄면 예전처럼 한 번에 사서 정해진 시각에 전부 판다
 * @param strategies      이월하는 전략 ID
 * @param roundTripCost   왕복 비용(수수료 0.015%×2 + 거래세 0.20%) — 단타 청산 시각에 이보다 많이 올랐으면 팔고, 아니면 넘긴다
 * @param hardStop        손절 폭(평단 기준 양수, 0.07 = −7%)
 * @param swingTakeProfit 스윙 단계 익절 폭(평단 기준, 0.05 = +5%)
 * @param trendSmaDays    2차 매수 추세 SMA(봉)
 * @param midSmaDays      3차 매수·중기 이탈 SMA(봉)
 * @param swingMaxDays    스윙 단계 최대 보유(이월 뒤 거래일, 0이면 무기한)
 * @param midMaxDays      중기 단계 최대 보유(0이면 무기한 — 추세가 이어지면 장기로)
 * @param buySlack        추가 매수 지정가 여유(최우선 매도호가 × (1 + buySlack))
 * @param sellSlack       매도 지정가 여유(최우선 매수호가 × (1 − sellSlack))
 */
@ConfigurationProperties(prefix = "strategy.carry")
public record SwingCarryProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue({"VB-DAY", "ON-CLOSE"}) List<String> strategies,
        @DefaultValue("0.0023") double roundTripCost,
        @DefaultValue("0.07") double hardStop,
        @DefaultValue("0.05") double swingTakeProfit,
        @DefaultValue("20") int trendSmaDays,
        @DefaultValue("60") int midSmaDays,
        @DefaultValue("10") int swingMaxDays,
        @DefaultValue("0") int midMaxDays,
        @DefaultValue("0.005") double buySlack,
        @DefaultValue("0.01") double sellSlack
) {

    /** 이 전략이 이월 대상인가(켜져 있을 때만). */
    public boolean carries(String strategyId) {
        return enabled && strategyId != null
                && strategies.stream().anyMatch(s -> s.equalsIgnoreCase(strategyId.toUpperCase(Locale.ROOT)));
    }

    SwingCarryMath.Rules rules() {
        return new SwingCarryMath.Rules(hardStop, swingTakeProfit, trendSmaDays, midSmaDays, swingMaxDays, midMaxDays);
    }
}

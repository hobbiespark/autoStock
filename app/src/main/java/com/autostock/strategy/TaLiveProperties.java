package com.autostock.strategy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * 차트 기법 5종(TA-H1~H5) 라이브 설정 — 규칙과 종목은 트랙 H 사전 선언 그대로(aiDoc/multi-strategy-paper-2026-10-16.md 3-3).
 *
 * @param enabled   기본 꺼짐 — 시작 스크립트가 켠다
 * @param symbols   종목 — 트랙 H와 같은 5종목
 * @param minBars   이보다 봉이 적으면 그 종목은 판단하지 않는다(SMA200·볼린저 120일 워밍업)
 * @param buySlack  장중 돌파 매수 지정가 여유(체결가 × (1 + buySlack))
 * @param sellSlack 매도 지정가 여유(기준가 × (1 − sellSlack))
 */
@ConfigurationProperties(prefix = "strategy.ta")
public record TaLiveProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue({"005930", "000660", "035420", "035720", "069500"}) List<String> symbols,
        @DefaultValue("250") int minBars,
        @DefaultValue("0.005") double buySlack,
        @DefaultValue("0.01") double sellSlack
) {
}

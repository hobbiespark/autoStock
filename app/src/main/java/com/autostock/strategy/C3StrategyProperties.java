package com.autostock.strategy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * C3 라이브 전략(시계열 모멘텀 + KODEX200 SMA200 국면필터 + 변동성 타게팅) 설정.
 *
 * <p>{@code enabled} 기본값이 false인 이유: 이 전략은 아직 자택망(사설 회선) 환경에서의
 * 라이브 검증을 거치지 않았다 — 게이트①(백테스트) 후보로 동결됐을 뿐, 실계좌 주문 경로를
 * 태운 적은 없다. 운영자가 검증 후 명시적으로 {@code strategy.c3.enabled=true}로 켜야 한다.
 *
 * @param enabled              전략 활성화 여부 (기본 false — 명시적 활성화 필요)
 * @param symbols              이 전략이 매매 판단을 내리는 종목 코드 목록
 * @param lookbackN             모멘텀 비교 기준 시점(N봉 전). TODO: 지금은 고정값이다 —
 *                              분기별로 walk-forward 재검증을 돌려 N 후보(60/120/200) 중
 *                              최적값을 재선정하는 절차를 아직 자동화하지 않았다(수동 배포).
 * @param decisionIntervalDays 판단 주기(봉 수) — 이 기간마다만 모멘텀을 재평가한다
 * @param targetVolAnnual      변동성 타게팅 목표 연변동성 (예: 0.20 = 연 20%)
 * @param regimeIndexSymbol    국면 필터 판정에 쓰는 대표 지수 종목코드 (기본 KODEX200)
 * @param regimeSmaDays        국면 필터 SMA 창 길이(봉 수)
 */
@ConfigurationProperties(prefix = "strategy.c3")
public record C3StrategyProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue({"005930", "000660", "035420", "035720", "069500"}) List<String> symbols,
        @DefaultValue("120") int lookbackN,
        @DefaultValue("21") int decisionIntervalDays,
        @DefaultValue("0.20") double targetVolAnnual,
        @DefaultValue("069500") String regimeIndexSymbol,
        @DefaultValue("200") int regimeSmaDays
) {
}

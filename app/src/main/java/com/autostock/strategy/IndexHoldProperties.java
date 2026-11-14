package com.autostock.strategy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 지수 보유 전략(KODEX-HOLD) 설정 — 경로 C(ADR-16·D-31)의 전략 슬리브 목표 구조 "KODEX200 보유 + 리스크 오버레이".
 *
 * <p>기본 꺼짐. 시작 스크립트가 켠다. 슬리브 크기는 이 클래스가 아니라 {@code autostock.risk.multi-strategy.sleeves}의
 * {@code KODEX-HOLD} 줄이 정한다(1종목 예산 = 평가액 × fraction ÷ max-positions × 신호 비중).
 *
 * @param enabled 전략 활성화 여부(기본 false)
 * @param symbol  보유할 지수 ETF 종목코드(기본 069500 KODEX 200)
 */
@ConfigurationProperties(prefix = "strategy.hold")
public record IndexHoldProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("069500") String symbol
) {
}

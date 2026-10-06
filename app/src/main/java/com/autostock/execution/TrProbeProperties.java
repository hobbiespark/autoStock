package com.autostock.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 체결·미체결 TR 장중 실측(실행 계획 1.6) 설정 — {@link TrProbeJob}. aiDoc/market-hours-automation.md 참고.
 *
 * @param enabled     켜기. 꺼져 있으면 아무것도 하지 않는다(기본)
 * @param date        실측할 날(KST) — 이 날 하루만 돈다. 비어 있으면 돌지 않는다
 * @param at          이 시각 이후 첫 분에 한 번 돈다(14:50까지)
 * @param symbol      주문할 종목(1주) — 기본 KODEX 200
 * @param discountPct 현재가보다 몇 % 낮게 지정가를 낼지(체결되지 않게). 가격제한폭(±30%) 안이어야 해서 5~29만 허용
 * @param outputDir   응답 원문 저장 폴더 — 앱 작업 폴더(app) 기준 상대 경로면 리포의 docs/measured
 */
@ConfigurationProperties(prefix = "execution.tr-probe")
public record TrProbeProperties(
        @DefaultValue("false") boolean enabled,
        LocalDate date,
        @DefaultValue("10:00") LocalTime at,
        @DefaultValue("069500") String symbol,
        @DefaultValue("15") int discountPct,
        @DefaultValue("../docs/measured") String outputDir
) {
}

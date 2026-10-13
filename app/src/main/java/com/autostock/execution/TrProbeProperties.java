package com.autostock.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

/**
 * 주문 경로 실측(실행 계획 1.6, 매 거래일 점검) 설정 — {@link TrProbeJob}. aiDoc/auto-measurement.md 참고.
 *
 * @param enabled           켜기. 꺼져 있으면 아무것도 하지 않는다(기본)
 * @param from              이 날부터 매 거래일 한 번 돈다. 비어 있으면 돌지 않는다
 * @param until             이 날까지만(포함). 비어 있으면 끝없이. 하루만 재려면 {@code from}과 같게 둔다
 * @param at                이 시각 이후 첫 분에 한 번 돈다(14:50까지)
 * @param symbol            주문할 종목(1주) — 기본 KODEX 200. 지정가 매도와 체결 왕복을 하려면 이 종목을 1주 이상 보유해야 한다
 * @param discountPct       지정가를 현재가에서 몇 % 떨어뜨릴지(매수는 아래, 매도는 위 — 체결되지 않게). 가격제한폭(±30%) 안이어야
 *                          해서 5~29만 허용
 * @param outputDir         응답 원문 저장 폴더 — 그 아래 날짜(yyyyMMdd) 폴더에 쌓는다. 앱 작업 폴더(app) 기준 상대 경로
 * @param fillRoundTripDays 체결 왕복(시장가 1주 매수 → 체결 확인 → 시장가 1주 매도, 보유 수량 그대로)을 하는 요일. 그날 빠지면
 *                          (PC 정지·앱 꺼짐 등) 앱이 같은 주 다음 거래일에 한다. 비우면 안 한다
 * @param notifySuccess     점검이 모두 맞아도 텔레그램을 보낼지. 기본은 어긋나거나 확인할 일이 있을 때만 보낸다(매일 도는 점검의 소음 방지)
 */
@ConfigurationProperties(prefix = "execution.tr-probe")
public record TrProbeProperties(
        @DefaultValue("false") boolean enabled,
        LocalDate from,
        LocalDate until,
        @DefaultValue("09:30") LocalTime at,
        @DefaultValue("069500") String symbol,
        @DefaultValue("15") int discountPct,
        @DefaultValue("../data/measured/tr_probe") String outputDir,
        Set<DayOfWeek> fillRoundTripDays,
        @DefaultValue("false") boolean notifySuccess
) {

    /** 그날 체결 왕복을 하는가. */
    public boolean fillRoundTripOn(LocalDate day) {
        return fillRoundTripDays != null && fillRoundTripDays.contains(day.getDayOfWeek());
    }
}

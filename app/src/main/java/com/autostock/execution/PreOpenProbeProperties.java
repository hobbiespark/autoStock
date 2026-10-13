package com.autostock.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.LocalTime;
import java.util.List;

/**
 * 장전 키움 응답 실측 설정 — {@link PreOpenProbeJob}. aiDoc/auto-measurement.md 참고.
 *
 * @param enabled   켜기. 꺼져 있으면 아무것도 하지 않는다(기본)
 * @param from      거래일 이 시각부터(5분마다)
 * @param to        이 시각까지 — 이 시각의 틱에서 그날 요약을 남긴다
 * @param symbols   분봉(ka10080)을 잴 종목 — 첫 종목은 시세(ka10001)도 잰다. 기본: 삼성전자(NXT 거래 종목)·KODEX 200(ETF, NXT 없음)
 * @param outputDir 기록 폴더(날짜별 jsonl·요약) — 앱 작업 폴더(app) 기준 상대 경로
 */
@ConfigurationProperties(prefix = "execution.pre-open-probe")
public record PreOpenProbeProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("07:30") LocalTime from,
        @DefaultValue("08:40") LocalTime to,
        @DefaultValue({"005930", "069500"}) List<String> symbols,
        @DefaultValue("../data/measured/pre_open") String outputDir
) {
}

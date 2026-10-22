package com.autostock.monitor;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;

/**
 * 성과 측정 시작일(2026-10-22, 사용자 "금액 한도 설정에 의한 성과 추이 초기화 후 다시 측정 필요") — {@code monitor.performance.*}.
 *
 * <p>10/21에 운용 자금을 2,000만 원으로 묶었다(aiDoc/multi-strategy-paper-2026-10-16.md 8절). 그 전 기록은 계좌 전체(약 4.86억)
 * 기준 사이징이라 같은 추이에 이어 붙이면 섞인다. 그래서 성과 추이 화면(API)과 일일 리포트의 누적 성과는 이 날짜부터 센다.
 * <b>지난 기록은 지우지 않는다</b> — daily_performance·strategy_fills는 그대로 두고 보는 범위만 자른다(되돌리려면 값을 비우면 된다).
 *
 * @param since 이 날(KST)부터 센다. 비우면 예전처럼 기간(days)으로만 자른다
 */
@ConfigurationProperties(prefix = "monitor.performance")
public record PerformanceProperties(LocalDate since) {

    /** 시작일 없음 — 시험·예전 생성자용. */
    public static PerformanceProperties none() {
        return new PerformanceProperties(null);
    }

    /** from과 시작일 중 늦은 날. */
    public LocalDate clamp(LocalDate from) {
        return since != null && since.isAfter(from) ? since : from;
    }
}

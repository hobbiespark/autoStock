package com.autostock.monitor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * 성과 측정 시작일(2026-10-22, 사용자 "금액 한도 설정에 의한 성과 추이 초기화 후 다시 측정 필요") — {@code monitor.performance.*}.
 *
 * <p>10/21에 운용 자금을 2,000만 원으로 묶었다(aiDoc/multi-strategy-paper-2026-10-16.md 8절). 그 전 기록은 계좌 전체(약 4.86억)
 * 기준 사이징이라 같은 추이에 이어 붙이면 섞인다. 그래서 일일 리포트의 누적 성과는 이 날짜부터 센다.
 * <b>지난 기록은 지우지 않는다</b> — daily_performance·strategy_fills는 그대로 두고 보는 범위만 자른다(되돌리려면 값을 비우면 된다).
 *
 * <p><b>전략 변경 구간(2026-11-06, 사용자 "전략 변경 시 전후 수익률 비교 가능한 성과")</b>: {@link #periods}에 변경 시점마다 한 줄
 * (시작일·변경 내용·그때 운용 자금)을 둔다. 성과 추이 화면은 첫 구간부터 보여 주고 변경 시점에 선을 긋는다. 구간 비교
 * ({@code GET /api/performance/periods})는 운용 자금 대비 수익률·거래당 수익률로 금액 규모가 달라도 비교한다.
 * <b>전략·예산을 바꿀 때 여기에 한 줄을 더한다</b>(aiDoc/strategy-change-performance.md).
 *
 * @param since   일일 리포트 누적 성과의 시작일(KST). 비우면 예전처럼 기간(days)으로만 자른다
 * @param periods 전략 변경 구간(시작일 오름차순으로 정렬해 쓴다). 비우면 구간 비교 없음
 */
@ConfigurationProperties(prefix = "monitor.performance")
public record PerformanceProperties(LocalDate since, List<Period> periods) {

    @ConstructorBinding
    public PerformanceProperties {
        periods = periods == null ? List.of()
                : periods.stream().sorted(Comparator.comparing(Period::since)).toList();
    }

    /** 구간 없는 생성자 — 시험·예전 호출부용. */
    public PerformanceProperties(LocalDate since) {
        this(since, List.of());
    }

    /** 시작일 없음 — 시험·예전 생성자용. */
    public static PerformanceProperties none() {
        return new PerformanceProperties(null, List.of());
    }

    /** from과 시작일 중 늦은 날(일일 리포트 누적). */
    public LocalDate clamp(LocalDate from) {
        return since != null && since.isAfter(from) ? since : from;
    }

    /** 성과 추이 화면의 시작 하한 — 구간이 있으면 첫 구간 시작일(변경 전후를 함께 보려고), 없으면 {@link #since}. */
    public LocalDate trendClamp(LocalDate from) {
        LocalDate floor = periods.isEmpty() ? since : periods.get(0).since();
        return floor != null && floor.isAfter(from) ? floor : from;
    }

    /**
     * 전략 변경 구간 하나.
     *
     * @param since      이 날(KST)부터 이 구간 — 다음 구간 전날까지
     * @param label      변경 내용(화면 표·차트 선 이름)
     * @param capitalKrw 그 구간의 운용 자금(원) — 운용 자금 대비 수익률의 분모. 0이면 그 수익률을 보이지 않는다
     */
    public record Period(LocalDate since, String label, long capitalKrw) {
        public Period {
            if (since == null) {
                throw new IllegalArgumentException("monitor.performance.periods[].since가 비었다");
            }
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("monitor.performance.periods[].label이 비었다: " + since);
            }
            if (capitalKrw < 0) {
                throw new IllegalArgumentException("monitor.performance.periods[].capital-krw는 0 이상: " + since);
            }
        }
    }
}

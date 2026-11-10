package com.autostock.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * 미국 예정 발표가 있는 밤(E1, aiDoc/global-event-filter-plan.md 4장·6-2) — FOMC 결정·CPI·고용 보고서가 한국의 그날 밤에
 * 발표되면 "사건 밤"이다. {@code ON-CLOSE}는 사건 밤에 신규 매수를 하지 않는다(069500 밤사이 갭: 분산 1.59배, 평균 낮음).
 *
 * <p>판정(실험 {@code GlobalEventFilterExperimentTest}와 같다): 오늘(한국 거래일) ≤ 미국 발표일 &lt; 다음 한국 거래일이면 사건 밤.
 * 미국 CPI·고용은 미국 날짜 08:30 ET(한국 그날 21:30·22:30), FOMC는 14:00 ET(한국 다음 날 03:00·04:00)라 모두 오늘 종가와
 * 다음 거래일 시가 사이에 든다. 금요일·연휴 앞이면 그 사이 미국 날짜가 모두 들어간다.
 *
 * <p>일정은 {@code classpath:macro/us-event-schedule.csv}(연 1회 {@code scripts/fetch_macro_event_data.py --schedule}로 생성).
 * 파일이 덮지 않는 연도의 밤은 판정할 수 없으므로 막지 않고 "일정 없음"을 알린다(예전 동작 유지).
 */
@Component
public class UsEventNights {

    private static final Logger log = LoggerFactory.getLogger(UsEventNights.class);
    static final String RESOURCE = "macro/us-event-schedule.csv";

    /** 판정 결과. events는 그 밤에 든 미국 발표(예: "2026-11-10 CPI"). */
    public record Verdict(boolean eventNight, boolean covered, List<String> events) {
    }

    private final boolean enabled;
    private final TreeMap<LocalDate, List<String>> schedule;
    private final Set<Integer> coveredYears;

    @org.springframework.beans.factory.annotation.Autowired
    public UsEventNights(@Value("${strategy.on.skip-us-event-nights:true}") boolean enabled) {
        this(enabled, UsEventNights.class.getClassLoader().getResourceAsStream(RESOURCE));
    }

    /** 시험용 — 일정 CSV 스트림을 직접 준다(null이면 일정 없음). */
    UsEventNights(boolean enabled, InputStream csv) {
        this.enabled = enabled;
        this.schedule = new TreeMap<>();
        this.coveredYears = new HashSet<>();
        if (csv == null) {
            log.warn("미국 발표 일정 {} 없음 — ON-CLOSE 사건 밤 건너뛰기를 적용하지 못함", RESOURCE);
            return;
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(csv, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.strip();
                if (line.startsWith("# 덮는 연도:")) {
                    for (String y : line.substring(line.indexOf(':') + 1).split(",")) {
                        if (!y.isBlank()) {
                            coveredYears.add(Integer.parseInt(y.strip()));
                        }
                    }
                    continue;
                }
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("date,")) {
                    continue;
                }
                String[] cols = line.split(",");
                schedule.computeIfAbsent(LocalDate.parse(cols[0]), d -> new ArrayList<>()).add(cols[1].strip());
            }
        } catch (IOException e) {
            throw new IllegalStateException("미국 발표 일정 읽기 실패: " + RESOURCE, e);
        }
        log.info("미국 발표 일정 {}건, 덮는 연도 {} — ON-CLOSE 사건 밤 건너뛰기 {}", schedule.values().stream()
                .mapToInt(List::size).sum(), coveredYears, enabled ? "켜짐" : "꺼짐");
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * today 종가 → nextTradingDay 시가 사이에 미국 예정 발표가 있나.
     *
     * @param today          오늘(한국 거래일)
     * @param nextTradingDay 다음 한국 거래일(today보다 뒤)
     */
    public Verdict check(LocalDate today, LocalDate nextTradingDay) {
        for (LocalDate d = today; d.isBefore(nextTradingDay); d = d.plusDays(1)) {
            if (!coveredYears.contains(d.getYear())) {
                return new Verdict(false, false, List.of());
            }
        }
        List<String> events = new ArrayList<>();
        schedule.subMap(today, true, nextTradingDay, false)
                .forEach((d, kinds) -> kinds.forEach(k -> events.add(d + " " + k)));
        return new Verdict(!events.isEmpty(), true, List.copyOf(events));
    }
}

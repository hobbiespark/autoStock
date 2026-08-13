package com.autostock.marketdata;

import com.autostock.common.util.TradingCalendar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 거래일 판정의 marketdata 모듈 공개 API — "DB 우선, 하드코딩 폴백" 전략을 구현한다.
 *
 * <h2>판정 순서</h2>
 * <ol>
 *   <li>주말(토·일)이면 곧바로 거래일 아님 — DB를 볼 필요조차 없다.</li>
 *   <li>해당 연도의 특일 데이터가 {@code market_holidays}에 있으면 DB 기준으로 판정한다
 *       ({@link HolidaySyncService}가 매년 11월에 채워 넣은 데이터, 또는 수동(MANUAL) 등록분).</li>
 *   <li>해당 연도 데이터가 DB에 전혀 없으면(아직 동기화 전, 또는 배치가 실패해 비어 있는 등)
 *       {@link TradingCalendar}의 하드코딩 목록으로 폴백한다 — 서비스 중단보다는 "정확도가
 *       떨어질 수 있는 값이라도 응답하는" 쪽을 택한 설계 판단이다. 폴백이 발생했다는 사실은
 *       연도당 1회만 경고 로그로 남긴다(운영자가 "동기화 필요"를 알아채도록 하되, 매 호출마다
 *       로그가 쏟아지는 것은 막는다).</li>
 * </ol>
 *
 * <h2>캐시</h2>
 * 연도 단위로 휴장일 집합을 인메모리에 캐시한다({@code Map<Integer, Optional<Set<LocalDate>>>}) —
 * {@code isTradingDay}가 호출될 때마다 DB를 왕복하면 RiskGate(모든 시그널마다 호출)·
 * C3LiveStrategy(장중 스케줄) 양쪽에서 불필요한 조회가 반복되기 때문이다. "DB에 데이터
 * 없음"이라는 결과도 캐시한다({@code Optional.empty()}) — 그래야 폴백 경로도 매 호출마다
 * DB를 다시 두드리지 않는다. {@link HolidaySyncService}가 동기화를 마치면 {@link #evictYear}로
 * 해당 연도 캐시를 무효화해, 다음 조회부터 새 데이터가 즉시 반영되게 한다.
 *
 * <h2>{@link #isMarketHours}는 그대로 위임</h2>
 * 정규장 시간대(09:00~15:30 KST) 판정은 캘린더(휴장일)와 무관한 "시각"만의 문제라 DB를
 * 볼 이유가 없다 — {@link TradingCalendar#isMarketHours}를 그대로 위임 호출한다.
 */
@Service
public class MarketCalendarService {

    private static final Logger log = LoggerFactory.getLogger(MarketCalendarService.class);

    private final MarketHolidayRepository repository;

    /** 연도별 휴장일 집합 캐시. Optional.empty()는 "DB에 해당 연도 데이터 없음(폴백 필요)"을 뜻한다. */
    private final Map<Integer, Optional<Set<LocalDate>>> yearCache = new ConcurrentHashMap<>();

    /** 폴백 경고 로그를 연도당 1회만 남기기 위한 기록 — evictYear 시 함께 지워 재평가되게 한다. */
    private final Set<Integer> fallbackWarned = ConcurrentHashMap.newKeySet();

    public MarketCalendarService(MarketHolidayRepository repository) {
        this.repository = repository;
    }

    /**
     * 주어진 날짜가 거래일인지 판정한다. 판정 순서는 클래스 설명 "판정 순서" 절 참고.
     */
    public boolean isTradingDay(LocalDate date) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return false;
        }

        int year = date.getYear();
        Optional<Set<LocalDate>> holidays = yearHolidays(year);
        if (holidays.isEmpty()) {
            if (fallbackWarned.add(year)) {
                log.warn("market_holidays에 {}년 데이터 없음 — TradingCalendar 하드코딩으로 폴백(동기화 필요, "
                        + "HolidaySyncService.syncYear({}) 수동 실행 고려)", year, year);
            }
            return TradingCalendar.isTradingDay(date);
        }
        return !holidays.get().contains(date);
    }

    /** 정규장 시간대(09:00~15:30 KST) 판정 — 클래스 설명 참고, TradingCalendar에 그대로 위임. */
    public boolean isMarketHours(LocalDateTime dateTime) {
        return TradingCalendar.isMarketHours(dateTime);
    }

    /**
     * 특정 연도의 캐시를 무효화한다. {@link HolidaySyncService}가 동기화(신규 등록 또는
     * 갱신)를 마친 뒤 호출해, 다음 {@link #isTradingDay} 호출부터 최신 DB 값이 반영되게 한다.
     * 폴백 경고 기록도 함께 지운다 — 동기화 직후에는 더 이상 폴백 상태가 아닐 수 있으므로,
     * 그래도 여전히 데이터가 없다면(예: 동기화가 부분 실패해 0건) 경고가 다시 나올 수 있어야 한다.
     */
    public void evictYear(int year) {
        yearCache.remove(year);
        fallbackWarned.remove(year);
        log.info("MarketCalendarService 캐시 무효화: {}년", year);
    }

    private Optional<Set<LocalDate>> yearHolidays(int year) {
        return yearCache.computeIfAbsent(year, this::loadYear);
    }

    private Optional<Set<LocalDate>> loadYear(int year) {
        LocalDate from = LocalDate.of(year, 1, 1);
        LocalDate to = LocalDate.of(year, 12, 31);
        List<MarketHolidayEntity> rows = repository.findByHolidayDateBetween(from, to);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Set<LocalDate> dates = rows.stream()
                .map(MarketHolidayEntity::getHolidayDate)
                .collect(Collectors.collectingAndThen(Collectors.toSet(), Collections::unmodifiableSet));
        return Optional.of(dates);
    }
}

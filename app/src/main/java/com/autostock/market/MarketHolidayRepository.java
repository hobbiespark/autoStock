package com.autostock.market;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

/**
 * {@link MarketHolidayEntity} 조회/저장 — {@link MarketCalendarService}(연도 단위 조회)와
 * {@link HolidaySyncService}(upsert)가 사용한다.
 *
 * <p>기본키가 {@link LocalDate}(holidayDate)라 {@code findById(LocalDate)}로 단건 upsert 대상
 * 조회가 가능하다 — 별도의 existsBy/findBy를 추가하지 않고 표준 findById를 그대로 쓴다.
 */
public interface MarketHolidayRepository extends JpaRepository<MarketHolidayEntity, LocalDate> {

    /** 연도 단위 조회 — {@code MarketCalendarService}가 한 해치 휴장일 집합을 캐시에 올릴 때 사용. */
    List<MarketHolidayEntity> findByHolidayDateBetween(LocalDate from, LocalDate to);

    /** 특정 날짜가 이미 등록돼 있는지 — 존재 여부만 필요한 호출부의 가벼운 조회용. */
    boolean existsByHolidayDate(LocalDate holidayDate);
}

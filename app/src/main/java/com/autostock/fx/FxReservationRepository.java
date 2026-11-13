package com.autostock.fx;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 외화 예약 기록(V34). */
public interface FxReservationRepository extends JpaRepository<FxReservationEntity, Long> {

    /** 그 통화의 열린 예약 가운데 가장 최근에 신청한 것 — 검토 알림의 기준 예약(설계 6장). */
    Optional<FxReservationEntity> findFirstByCurrencyAndStatusOrderByRequestedOnDescIdDesc(
            FxCurrency currency, FxReservationEntity.Status status);

    /** 노출액 계산 재료: 열린 예약 전부 + 그 기간에 체결한 예약(설계 5장). */
    List<FxReservationEntity> findByStatusOrFilledOnBetween(FxReservationEntity.Status status, LocalDate from, LocalDate to);

    /** 이 상태들의 예약(화면·정산). */
    List<FxReservationEntity> findByStatusInOrderByRequestedOnAscIdAsc(Collection<FxReservationEntity.Status> statuses);
}

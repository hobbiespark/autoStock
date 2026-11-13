package com.autostock.fx;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

/** 보낸 외화 알림 기록(V34) — 보내기 전에 보고, 보낸 뒤 남긴다. */
public interface FxAlertLogRepository extends JpaRepository<FxAlertLogEntity, Long> {

    boolean existsByKindAndCurrencyAndAlertKey(String kind, String currency, String alertKey);

    /** 그 통화로 이 시각 이후 보낸 그 종류 알림이 있는가 — 통화별 하루 1통(설계 6장 C6). */
    boolean existsByKindAndCurrencyAndSentAtGreaterThanEqual(String kind, String currency, Instant since);
}

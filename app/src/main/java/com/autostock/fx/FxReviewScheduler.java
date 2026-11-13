package com.autostock.fx;

import com.autostock.common.event.FxAlert;
import com.autostock.common.util.MarketConstants;
import com.autostock.macrointel.EcosClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;
import java.util.Optional;

/**
 * 직접매수 검토 감시 — 설계 3·6장(D-28). 평일 09~18시 매시 10분에 두 통화의 ECOS 최신값을 받아 {@link FxReviewRule}로 판정한다.
 *
 * <p>ECOS는 하루 한 값이라 같은 관측일을 여러 번 판정해도 결과는 같다. 같은 예약·같은 관측일로 이미 보냈으면 건너뛰고
 * ({@code fx_alert_log} 유일 키, BE BC-17), 보낼 때는 기록을 먼저 남긴 뒤 {@link FxAlert}를 발행한다(monitor 리스너가 텔레그램으로 보냄).
 * 주문을 내지 않는다.
 */
@Component
public class FxReviewScheduler {

    private static final Logger log = LoggerFactory.getLogger(FxReviewScheduler.class);

    private final FxAccumulationProperties properties;
    private final EcosClient ecos;
    private final FxReservationRepository reservations;
    private final FxDirectBuyRepository buys;
    private final FxAlertLogRepository alertLog;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public FxReviewScheduler(FxAccumulationProperties properties, EcosClient ecos, FxReservationRepository reservations,
                             FxDirectBuyRepository buys, FxAlertLogRepository alertLog, ApplicationEventPublisher publisher,
                             Clock clock) {
        this.properties = properties;
        this.ecos = ecos;
        this.reservations = reservations;
        this.buys = buys;
        this.alertLog = alertLog;
        this.publisher = publisher;
        this.clock = clock;
    }

    /** 평일 09:10~18:10 매시(설계 3장). 알림이 꺼져 있거나 시작일 전이면 아무것도 하지 않는다. */
    @Scheduled(cron = "0 10 9-18 * * MON-FRI", zone = "Asia/Seoul")
    public void scheduledReview() {
        if (!properties.activeOn(LocalDate.now(clock.withZone(MarketConstants.KST)))) {
            return;
        }
        try {
            reviewPass();
        } catch (RuntimeException e) {
            log.error("외화 직접매수 검토 판정 실패", e);
        }
    }

    /** 두 통화를 한 번씩 판정한다. 일정 밖에서도 시험·수동 점검으로 부를 수 있다. */
    @Transactional
    public void reviewPass() {
        LocalDate today = LocalDate.now(clock.withZone(MarketConstants.KST));
        YearMonth month = YearMonth.from(today);
        long exposure = FxExposure.of(month,
                reservations.findByStatusOrFilledOnBetween(FxReservationEntity.Status.OPEN, month.atDay(1), month.atEndOfMonth())
                        .stream().map(FxReservationEntity::exposureView).toList(),
                buys.findByBoughtOnBetweenOrderByBoughtOnAscIdAsc(month.atDay(1), month.atEndOfMonth())
                        .stream().map(FxDirectBuyEntity::exposureView).toList());
        for (FxCurrency currency : FxCurrency.values()) {
            reviewCurrency(currency, today, month, exposure);
        }
    }

    private void reviewCurrency(FxCurrency currency, LocalDate today, YearMonth month, long exposure) {
        Optional<FxReservationEntity> found = reservations.findFirstByCurrencyAndStatusOrderByRequestedOnDescIdDesc(
                currency, FxReservationEntity.Status.OPEN);
        if (found.isEmpty()) {
            return;   // C2: 기준 예약 없음
        }
        FxReservationEntity reservation = found.get();
        Optional<EcosClient.Observation> observation = ecos.fetchLatest(FxCurrency.ECOS_STAT_CODE, currency.ecosItemCode());
        BigDecimal publicRate = observation.map(EcosClient.Observation::value).orElse(null);
        LocalDate observedOn = observation.map(EcosClient.Observation::date).orElse(null);
        String key = observedOn == null ? null : FxAlertLogEntity.reviewKey(reservation.getId(), observedOn);
        if (key != null && alertLog.existsByKindAndCurrencyAndAlertKey(FxAlertLogEntity.KIND_REVIEW, currency.name(), key)) {
            return;   // 이 관측으로 이미 보냄
        }
        Instant startOfToday = today.atStartOfDay(MarketConstants.KST).toInstant();
        FxReviewRule.Result result = FxReviewRule.evaluate(new FxReviewRule.Input(
                today, properties.reviewStartDay(), true,
                reservation.getRequestRate(), reservation.getReserveRate(), reservation.getSpread(), publicRate,
                reservation.getLastReviewBelow(),
                buys.existsByCurrencyAndStatusAndBoughtOnBetween(currency, FxDirectBuyEntity.Status.DONE,
                        month.atDay(1), month.atEndOfMonth()),
                exposure, properties.directBuyKrw(currency), properties.monthlyCapKrw(),
                alertLog.existsByKindAndCurrencyAndSentAtGreaterThanEqual(FxAlertLogEntity.KIND_REVIEW, currency.name(),
                        startOfToday)));
        if (!Objects.equals(result.nextLastBelow(), reservation.getLastReviewBelow())) {
            reservation.recordReview(result.nextLastBelow(), clock.instant());
            reservations.save(reservation);
        }
        log.info("외화 검토 판정: {} R{} 관측일 {} → {}", currency, reservation.getId(), observedOn, result.reason());
        if (!result.send()) {
            return;
        }
        String message = FxMessages.review(currency, reservation, result, publicRate, observedOn, exposure,
                properties.monthlyCapKrw(), properties.directBuyKrw(currency));
        alertLog.saveAndFlush(new FxAlertLogEntity(FxAlertLogEntity.KIND_REVIEW, currency.name(), key, clock.instant()));
        publisher.publishEvent(new FxAlert(FxAlertLogEntity.KIND_REVIEW, currency.name(), message, clock.instant()));
    }
}

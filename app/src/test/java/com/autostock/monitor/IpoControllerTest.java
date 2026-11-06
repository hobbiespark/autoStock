package com.autostock.monitor;

import java.time.Instant;
import com.autostock.ipo.DartClient;
import com.autostock.ipo.DartProperties;
import com.autostock.ipo.IpoAlertLogRepository;
import com.autostock.ipo.IpoDealCommandService;
import com.autostock.ipo.IpoDealEntity;
import com.autostock.ipo.IpoDealRepository;
import com.autostock.ipo.IpoFilterProperties;
import com.autostock.ipo.IpoRecommendation;
import com.autostock.ipo.IpoStatus;
import com.autostock.ipo.IpoSyncScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IpoController — GET /api/ipo(status 필터), POST 기록/지표 입력(FE-3, PLAN.md ADR-9 트랙 E2)이
 * Domain Entity를 View DTO로 정확히 옮겨 담는지, 존재하지 않는 id는 404를 반환하는지, 지표
 * 입력이 즉시 필터를 재평가하는지 검증한다.
 */
class IpoControllerTest {

    private final IpoDealRepository repository = mock(IpoDealRepository.class);
    private final IpoSyncScheduler syncScheduler = new IpoSyncScheduler(
            new DartProperties(true, "test-key", 14),
            new IpoFilterProperties(new BigDecimal("500"), new BigDecimal("0.20")),
            mock(DartClient.class), repository, mock(IpoAlertLogRepository.class), mock(ApplicationEventPublisher.class),
            Clock.systemUTC());
    private final IpoController controller =
            new IpoController(repository, new IpoDealCommandService(repository, syncScheduler, Clock.systemUTC()));

    @Test
    void status_생략시_전체_목록을_반환한다() {
        IpoDealEntity entity = new IpoDealEntity("01359815", "한울반도체", "20260910000583", "DART", Instant.now());
        when(repository.findAllByOrderBySubscriptionStartDesc()).thenReturn(List.of(entity));

        var result = controller.deals(null);

        assertEquals(1, result.size());
        assertEquals("한울반도체", result.get(0).corpName());
        assertEquals("PENDING", result.get(0).recommendation());
    }

    @Test
    void 상장사_유상증자로_판정된_딜은_목록에서_뺀다() {
        IpoDealEntity rights = new IpoDealEntity("01359815", "한울반도체", "20260910000583", "DART", Instant.now());
        rights.classifyOffering(com.autostock.ipo.OfferingKind.RIGHTS, Instant.now());
        IpoDealEntity ipo = new IpoDealEntity("01801026", "브릴스", "20260916000234", "DART", Instant.now());
        ipo.applyDemandForecast(new BigDecimal("1187.74"), new BigDecimal("0.2175"), "20260916000234", Instant.now());
        when(repository.findAllByOrderBySubscriptionStartDesc()).thenReturn(List.of(rights, ipo));

        var result = controller.deals(null);

        assertEquals(1, result.size());
        assertEquals("브릴스", result.get(0).corpName());
        assertEquals("DART", result.get(0).metricsSource()); // 화면이 "자동 입력"으로 표시한다
        assertEquals("20260916000234", result.get(0).metricsRceptNo());
    }

    /**
     * 옵토닉스 꼴(2026-11-06) — 같은 회사의 옛 신고서 행(일정 없음, 예정)과 최신 행(일정 있음, 청약 중). 회사마다 한 줄, 최종 상태 탭에만,
     * 옛 행에 저장된 배정 기록도 보인다.
     */
    private List<IpoDealEntity> optonicsRows() {
        Instant now = Instant.now();
        IpoDealEntity old = new IpoDealEntity("01555555", "옵토닉스", "20261020000100", "DART", now);
        old.applyRecord(20, new BigDecimal("120000"), 2, null, null, null, now);
        IpoDealEntity latest = new IpoDealEntity("01555555", "옵토닉스", "20261028000200", "DART", now);
        latest.applyOfferingDetail(new com.autostock.ipo.DartClient.OfferingDetail("20261028000200",
                java.time.LocalDate.of(2026, 11, 5), java.time.LocalDate.of(2026, 11, 6), java.time.LocalDate.of(2026, 11, 10),
                "한국투자증권", new BigDecimal("12000"), null), now);
        latest.updateStatus(IpoStatus.SUBSCRIBING, now);
        return List.of(latest, old);
    }

    @Test
    void 같은_회사의_신고서_여러_행은_최종_상태_한_줄로_보인다() {
        when(repository.findAllByOrderBySubscriptionStartDesc()).thenReturn(optonicsRows());

        var all = controller.deals(null);
        assertEquals(1, all.size());
        assertEquals("SUBSCRIBING", all.get(0).status());
        assertEquals("20261028000200", all.get(0).rceptNo(), "대표 행 = 일정이 있는 최신 신고서 — 입력도 이 행에 저장된다");
        assertEquals(java.time.LocalDate.of(2026, 11, 5), all.get(0).subscriptionStart());
        assertEquals(Integer.valueOf(20), all.get(0).appliedQty(), "옛 행에 넣은 청약 기록도 보인다");
        assertEquals(Integer.valueOf(2), all.get(0).allocatedQty());

        assertEquals(1, controller.deals("SUBSCRIBING").size());
        assertEquals(0, controller.deals("UPCOMING").size(), "옛 행의 예정 상태로는 보이지 않는다");
    }

    @Test
    void 알수없는_status는_400을_던진다() {
        ApiException ex = assertThrows(ApiException.class, () -> controller.deals("NOPE"));
        assertEquals(ErrorCode.INVALID_PARAMETER, ex.code());
    }

    @Test
    void 존재하지않는_id_기록_요청은_NOT_FOUND다() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
                () -> controller.record(999L, new IpoRecordRequest(10, null, null, null, null, null)));

        assertEquals(ErrorCode.NOT_FOUND, ex.code());
    }

    @Test
    void 청약기록을_부분_갱신한다() {
        IpoDealEntity entity = new IpoDealEntity("01359815", "한울반도체", "20260910000583", "DART", Instant.now());
        when(repository.findById(1L)).thenReturn(Optional.of(entity));

        var response = controller.record(1L, new IpoRecordRequest(10, new BigDecimal("503000"), null, null, null, "메모"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(10, response.getBody().appliedQty());
        assertEquals(0, response.getBody().deposit().compareTo(new BigDecimal("503000")));
        assertEquals("메모", response.getBody().memo());
    }

    @Test
    void 배치와_동시_갱신_충돌은_전역_처리기로_전파된다() {
        IpoDealEntity entity = new IpoDealEntity("01359815", "한울반도체", "20260910000583", "DART", Instant.now());
        when(repository.findById(1L)).thenReturn(Optional.of(entity));
        when(repository.save(entity)).thenThrow(new ObjectOptimisticLockingFailureException(IpoDealEntity.class, 1L));

        // ApiExceptionHandler가 409 CONFLICT로 바꾼다(ApiExceptionHandlerTest)
        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> controller.metrics(1L, new IpoMetricsRequest(new BigDecimal("600"), null, null)));
    }

    @Test
    void 지표_입력은_즉시_필터를_재평가한다() {
        IpoDealEntity entity = new IpoDealEntity("01359815", "한울반도체", "20260910000583", "DART", Instant.now());
        when(repository.findById(1L)).thenReturn(Optional.of(entity));

        var response = controller.metrics(1L, new IpoMetricsRequest(new BigDecimal("600"), new BigDecimal("0.30"), null));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("RECOMMEND", response.getBody().recommendation());
    }

    @Test
    void 상장일만_입력하면_지표는_유지되고_상장일_도래시_LISTED가_된다() {
        IpoDealEntity entity = new IpoDealEntity("01359815", "한울반도체", "20260910000583", "DART", Instant.now());
        entity.applyMetrics(new BigDecimal("600"), new BigDecimal("0.30"), Instant.now());
        when(repository.findById(1L)).thenReturn(Optional.of(entity));

        var response = controller.metrics(1L, new IpoMetricsRequest(null, null, LocalDate.of(2020, 1, 2)));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(new BigDecimal("600"), response.getBody().institutionalCompetitionRate()); // 부분 갱신
        assertEquals(LocalDate.of(2020, 1, 2), response.getBody().listingDate());
        assertEquals("LISTED", response.getBody().status()); // 과거 상장일 → 즉시 LISTED
        assertEquals("MANUAL", response.getBody().metricsSource()); // 앞서 넣은 지표의 출처가 그대로다
    }

    @Test
    void 상장일만_입력하면_자동_입력된_지표의_출처는_그대로다() {
        IpoDealEntity entity = new IpoDealEntity("01801026", "브릴스", "20260825000476", "DART", Instant.now());
        entity.applyDemandForecast(new BigDecimal("1187.74"), new BigDecimal("0.2175"), "20260916000234", Instant.now());
        when(repository.findById(1L)).thenReturn(Optional.of(entity));

        var response = controller.metrics(1L, new IpoMetricsRequest(null, null, LocalDate.of(2026, 9, 25)));

        assertEquals("DART", response.getBody().metricsSource());
        assertEquals("20260916000234", response.getBody().metricsRceptNo());
    }
}

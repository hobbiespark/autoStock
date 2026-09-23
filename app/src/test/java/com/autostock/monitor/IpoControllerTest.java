package com.autostock.monitor;

import com.autostock.ipo.DartClient;
import com.autostock.ipo.DartProperties;
import com.autostock.ipo.IpoDealEntity;
import com.autostock.ipo.IpoDealRepository;
import com.autostock.ipo.IpoFilterProperties;
import com.autostock.ipo.IpoRecommendation;
import com.autostock.ipo.IpoStatus;
import com.autostock.ipo.IpoSyncScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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
            mock(DartClient.class), repository, mock(ApplicationEventPublisher.class), Clock.systemUTC());
    private final IpoController controller = new IpoController(repository, syncScheduler);

    @Test
    void status_생략시_전체_목록을_반환한다() {
        IpoDealEntity entity = new IpoDealEntity("01359815", "한울반도체", "20260910000583", "DART");
        when(repository.findAllByOrderBySubscriptionStartDesc()).thenReturn(List.of(entity));

        var result = controller.deals(null);

        assertEquals(1, result.size());
        assertEquals("한울반도체", result.get(0).corpName());
        assertEquals("PENDING", result.get(0).recommendation());
    }

    @Test
    void status_지정시_해당_상태만_조회한다() {
        when(repository.findByStatusOrderBySubscriptionStartAsc(IpoStatus.SUBSCRIBING)).thenReturn(List.of());

        controller.deals("SUBSCRIBING");

        verify(repository).findByStatusOrderBySubscriptionStartAsc(IpoStatus.SUBSCRIBING);
    }

    @Test
    void 알수없는_status는_400을_던진다() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.deals("NOPE"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void 존재하지않는_id_기록_요청은_404를_반환한다() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        var response = controller.record(999L, new IpoRecordRequest(10, null, null, null, null, null));

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void 청약기록을_부분_갱신한다() {
        IpoDealEntity entity = new IpoDealEntity("01359815", "한울반도체", "20260910000583", "DART");
        when(repository.findById(1L)).thenReturn(Optional.of(entity));

        var response = controller.record(1L, new IpoRecordRequest(10, new BigDecimal("503000"), null, null, null, "메모"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(10, response.getBody().appliedQty());
        assertEquals(0, response.getBody().deposit().compareTo(new BigDecimal("503000")));
        assertEquals("메모", response.getBody().memo());
    }

    @Test
    void 지표_입력은_즉시_필터를_재평가한다() {
        IpoDealEntity entity = new IpoDealEntity("01359815", "한울반도체", "20260910000583", "DART");
        when(repository.findById(1L)).thenReturn(Optional.of(entity));

        var response = controller.metrics(1L, new IpoMetricsRequest(new BigDecimal("600"), new BigDecimal("0.30"), null));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("RECOMMEND", response.getBody().recommendation());
    }

    @Test
    void 상장일만_입력하면_지표는_유지되고_상장일_도래시_LISTED가_된다() {
        IpoDealEntity entity = new IpoDealEntity("01359815", "한울반도체", "20260910000583", "DART");
        entity.applyMetrics(new BigDecimal("600"), new BigDecimal("0.30"));
        when(repository.findById(1L)).thenReturn(Optional.of(entity));

        var response = controller.metrics(1L, new IpoMetricsRequest(null, null, LocalDate.of(2020, 1, 2)));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(new BigDecimal("600"), response.getBody().institutionalCompetitionRate()); // 부분 갱신
        assertEquals(LocalDate.of(2020, 1, 2), response.getBody().listingDate());
        assertEquals("LISTED", response.getBody().status()); // 과거 상장일 → 즉시 LISTED
    }
}

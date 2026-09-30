package com.autostock.ipo;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

/**
 * IpoDealCommandService — 수동 입력(청약 기록·지표)의 조회-변경-재평가-저장이 한 트랜잭션 경계 안에서
 * 일어나는지, 없는 딜은 빈 결과를 돌려주는지 검증한다.
 */
class IpoDealCommandServiceTest {

    private final IpoDealRepository repository = mock(IpoDealRepository.class);
    private final IpoSyncScheduler syncScheduler = new IpoSyncScheduler(
            new DartProperties(true, "test-key", 14),
            new IpoFilterProperties(new BigDecimal("500"), new BigDecimal("0.20")),
            mock(DartClient.class), repository, mock(ApplicationEventPublisher.class), Clock.systemUTC());
    private final IpoDealCommandService service = new IpoDealCommandService(repository, syncScheduler, Clock.systemUTC());

    @Test
    void 없는_딜에_청약기록을_입력하면_빈_결과이고_저장하지_않는다() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        Optional<IpoDealEntity> result = service.recordMyDeal(999L,
                new IpoDealCommandService.RecordCommand(10, null, null, null, null, null));

        assertTrue(result.isEmpty());
        verify(repository, never()).save(any());
    }

    @Test
    void 청약기록은_부분_갱신되어_저장된다() {
        IpoDealEntity entity = new IpoDealEntity("01359815", "한울반도체", "20260910000583", "DART", Instant.now());
        when(repository.findById(1L)).thenReturn(Optional.of(entity));

        IpoDealEntity result = service.recordMyDeal(1L, new IpoDealCommandService.RecordCommand(
                10, new BigDecimal("503000"), null, null, null, "메모")).orElseThrow();

        assertEquals(10, result.getAppliedQty());
        assertEquals(0, result.getDeposit().compareTo(new BigDecimal("503000")));
        assertEquals("메모", result.getMemo());
        verify(repository).save(entity);
    }

    @Test
    void 지표_입력은_필터와_상태를_재평가해_저장한다() {
        IpoDealEntity entity = new IpoDealEntity("01359815", "한울반도체", "20260910000583", "DART", Instant.now());
        when(repository.findById(1L)).thenReturn(Optional.of(entity));

        IpoDealEntity result = service.updateMetrics(1L, new IpoDealCommandService.MetricsCommand(
                new BigDecimal("600"), new BigDecimal("0.30"), LocalDate.of(2020, 1, 2))).orElseThrow();

        assertEquals(IpoRecommendation.RECOMMEND, result.getRecommendation());
        assertEquals(IpoStatus.LISTED, result.getStatus()); // 과거 상장일 → 즉시 LISTED
        verify(repository).save(entity);
    }

    @Test
    void 없는_딜에_지표를_입력하면_빈_결과이고_저장하지_않는다() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        Optional<IpoDealEntity> result = service.updateMetrics(999L,
                new IpoDealCommandService.MetricsCommand(new BigDecimal("600"), null, null));

        assertTrue(result.isEmpty());
        verify(repository, never()).save(any());
    }

    @Test
    void 조회_변경_저장은_한_트랜잭션_경계로_묶인다() throws NoSuchMethodException {
        // 조회-변경-저장이 경계 없이 나뉘면 배치(IpoSyncScheduler.syncNow)와 끼어들어 갱신이 유실될 수 있다.
        assertNotNull(IpoDealCommandService.class.getMethod("recordMyDeal",
                Long.class, IpoDealCommandService.RecordCommand.class).getAnnotation(Transactional.class));
        assertNotNull(IpoDealCommandService.class.getMethod("updateMetrics",
                Long.class, IpoDealCommandService.MetricsCommand.class).getAnnotation(Transactional.class));
    }
}

package com.autostock.macrointel;

import com.autostock.common.event.MacroIndicator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MacroSyncScheduler 검증 — enabled=false no-op, 4개 지표 발행, 소스별 실패 격리(FRED 실패가
 * ECOS 수집을 막지 않음, 클래스 설명 "소스별 실패 격리" 참고).
 */
class MacroSyncSchedulerTest {

    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher publisher = published::add;

    private FredClient fredClient;
    private EcosClient ecosClient;

    @BeforeEach
    void setUp() {
        fredClient = mock(FredClient.class);
        ecosClient = mock(EcosClient.class);
        published.clear();
    }

    private MacroSyncScheduler schedulerWithEnabled(boolean enabled) {
        MacroIntelProperties properties = new MacroIntelProperties(enabled, "fk", "ek", 25.0, 35.0, 1450.0);
        return new MacroSyncScheduler(properties, fredClient, ecosClient, publisher);
    }

    @Test
    void enabled가_false면_아무_것도_수집하지_않는다() {
        MacroSyncScheduler scheduler = schedulerWithEnabled(false);

        scheduler.syncNow();

        assertTrue(published.isEmpty());
        verifyNoInteractions(fredClient, ecosClient);
    }

    @Test
    void 정상_수집시_4개_지표를_모두_발행한다() {
        when(fredClient.fetchLatest(FredClient.SERIES_VIX))
                .thenReturn(Optional.of(new FredClient.Observation(java.time.LocalDate.now(), new BigDecimal("18.5"))));
        when(fredClient.fetchLatest(FredClient.SERIES_DXY))
                .thenReturn(Optional.of(new FredClient.Observation(java.time.LocalDate.now(), new BigDecimal("103.2"))));
        when(ecosClient.fetchLatest(EcosClient.STAT_CODE_USDKRW))
                .thenReturn(Optional.of(new EcosClient.Observation(java.time.LocalDate.now(), new BigDecimal("1345.5"))));
        when(ecosClient.fetchLatest(EcosClient.STAT_CODE_BASE_RATE))
                .thenReturn(Optional.of(new EcosClient.Observation(java.time.LocalDate.now(), new BigDecimal("3.5"))));

        MacroSyncScheduler scheduler = schedulerWithEnabled(true);
        scheduler.syncNow();

        assertEquals(4, published.size());
        List<String> indicatorIds = published.stream()
                .map(e -> ((MacroIndicator) e).indicatorId())
                .toList();
        assertTrue(indicatorIds.containsAll(List.of(
                MacroSyncScheduler.INDICATOR_VIX, MacroSyncScheduler.INDICATOR_DXY,
                MacroSyncScheduler.INDICATOR_USDKRW, MacroSyncScheduler.INDICATOR_BASE_RATE)));
        published.forEach(e -> assertEquals("MARKET", ((MacroIndicator) e).scope()));
    }

    @Test
    void FRED_수집_실패해도_ECOS_수집은_정상_진행된다() {
        when(fredClient.fetchLatest(any())).thenThrow(new RuntimeException("FRED 강제 실패"));
        when(ecosClient.fetchLatest(EcosClient.STAT_CODE_USDKRW))
                .thenReturn(Optional.of(new EcosClient.Observation(java.time.LocalDate.now(), new BigDecimal("1345.5"))));
        when(ecosClient.fetchLatest(EcosClient.STAT_CODE_BASE_RATE))
                .thenReturn(Optional.of(new EcosClient.Observation(java.time.LocalDate.now(), new BigDecimal("3.5"))));

        MacroSyncScheduler scheduler = schedulerWithEnabled(true);
        scheduler.syncNow();

        // FRED 2건은 실패해서 발행되지 않지만 ECOS 2건은 정상 발행돼야 한다.
        assertEquals(2, published.size());
        List<String> indicatorIds = published.stream()
                .map(e -> ((MacroIndicator) e).indicatorId())
                .toList();
        assertTrue(indicatorIds.containsAll(List.of(
                MacroSyncScheduler.INDICATOR_USDKRW, MacroSyncScheduler.INDICATOR_BASE_RATE)));
    }

    @Test
    void 관측치가_없으면_해당_지표만_발행을_건너뛴다() {
        when(fredClient.fetchLatest(FredClient.SERIES_VIX)).thenReturn(Optional.empty());
        when(fredClient.fetchLatest(FredClient.SERIES_DXY))
                .thenReturn(Optional.of(new FredClient.Observation(java.time.LocalDate.now(), new BigDecimal("103.2"))));
        when(ecosClient.fetchLatest(any())).thenReturn(Optional.empty());

        MacroSyncScheduler scheduler = schedulerWithEnabled(true);
        scheduler.syncNow();

        assertEquals(1, published.size());
        assertEquals(MacroSyncScheduler.INDICATOR_DXY, ((MacroIndicator) published.get(0)).indicatorId());
    }
}

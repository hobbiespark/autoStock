package com.autostock.audit;

import com.autostock.common.event.Fill;
import com.autostock.common.event.Side;
import com.autostock.common.event.Signal;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import com.autostock.common.util.StockCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.event.EventListener;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 감사 기록(실행 계획 1.8) — 이벤트 타입별 스키마 버전, 저장 실패 카운터, 감사 대상 타입이 버전 표에 모두 있는지.
 */
class EventAuditListenerTest {

    private final EventRecordRepository repository = mock(EventRecordRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final EventAuditListener listener = new EventAuditListener(repository,
            new ObjectMapper().registerModule(new JavaTimeModule()), Clock.systemUTC(), registry);

    private static final Instant AT = Instant.parse("2026-10-06T00:05:00Z");

    @Test
    void 타입별_스키마_버전을_남긴다_Signal은_2_Fill은_1() {
        listener.on(new Signal("C3-MOMENTUM", new StockCode("005930"), Side.BUY, new Price(new BigDecimal("259500")),
                0.8, AT));
        listener.on(new Fill("k1", new BrokerOrderId("0119433"), new StockCode("005930"), Side.BUY, new Quantity(3),
                new Price(new BigDecimal("259500")), AT));

        ArgumentCaptor<EventRecord> saved = ArgumentCaptor.forClass(EventRecord.class);
        verify(repository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertEquals("Signal", saved.getAllValues().get(0).getEventType());
        assertEquals(2, saved.getAllValues().get(0).getSchemaVersion());
        assertEquals("Fill", saved.getAllValues().get(1).getEventType());
        assertEquals(1, saved.getAllValues().get(1).getSchemaVersion());
    }

    @Test
    void 저장에_실패하면_예외_없이_타입별로_센다() {
        when(repository.save(any())).thenThrow(new IllegalStateException("DB 연결 끊김(테스트)"));

        assertDoesNotThrow(() -> listener.on(new Fill("k1", new BrokerOrderId("0119433"), new StockCode("005930"),
                Side.BUY, new Quantity(3), new Price(new BigDecimal("259500")), AT)));

        assertEquals(1.0, registry.counter("audit.write.failure", "type", "Fill").count());
    }

    @Test
    void 감사_대상_이벤트_타입은_모두_스키마_버전_표에_있다() {
        List<String> audited = Arrays.stream(EventAuditListener.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(EventListener.class))
                .map(Method::getParameterTypes)
                .map(params -> params[0].getSimpleName())
                .toList();

        assertEquals(7, audited.size(), audited.toString());
        assertTrue(EventAuditListener.SCHEMA_VERSIONS.keySet().containsAll(audited),
                "버전 표에 없는 감사 대상: " + audited);
    }
}

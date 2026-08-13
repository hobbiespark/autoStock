package com.autostock.audit;

import com.autostock.common.event.Fill;
import com.autostock.common.event.MacroIndicator;
import com.autostock.common.event.MarketTick;
import com.autostock.common.event.NewsSentiment;
import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Signal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 모든 도메인 이벤트를 이벤트 스토어에 기록. (스키마 v1 고정)
 * 주의: 고빈도 MarketTick은 추후 비동기 배치 flush로 전환 (PLAN 6절).
 */
@Component
public class EventAuditListener {

    private static final int SCHEMA_V1 = 1;

    private final EventRecordRepository repository;
    private final ObjectMapper objectMapper;

    public EventAuditListener(EventRecordRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void on(MarketTick e) { store("MarketTick", e, e.timestamp()); }

    @EventListener
    public void on(Signal e) { store("Signal", e, e.timestamp()); }

    @EventListener
    public void on(OrderRequest e) { store("OrderRequest", e, e.timestamp()); }

    @EventListener
    public void on(Fill e) { store("Fill", e, e.timestamp()); }

    @EventListener
    public void on(MacroIndicator e) { store("MacroIndicator", e, e.timestamp()); }

    @EventListener
    public void on(NewsSentiment e) { store("NewsSentiment", e, e.timestamp()); }

    private void store(String type, Object event, Instant occurredAt) {
        try {
            repository.save(new EventRecord(type, SCHEMA_V1, objectMapper.writeValueAsString(event), occurredAt));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("이벤트 직렬화 실패: " + type, ex);
        }
    }
}

package com.autostock.audit;

import com.autostock.common.event.Fill;
import com.autostock.common.event.MacroIndicator;
import com.autostock.common.event.MarketTick;
import com.autostock.common.event.NewsSentiment;
import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Signal;
import com.autostock.common.event.SignalDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * 도메인 이벤트를 이벤트 스토어에 기록. 이벤트 타입별 스키마 버전을 함께 남긴다({@link #SCHEMA_VERSIONS}).
 * 주의: 고빈도 MarketTick은 추후 비동기 배치 flush로 전환 (PLAN 6절).
 *
 * <p><b>{@code @Async}(PLAN ADR-5)</b>: 감사 기록은 매매 판단에 필요한 값을 되돌려주지
 * 않는 "부수 효과"라서, 이벤트 발행 스레드(시그널→리스크→주문으로 이어지는 매매 핫패스)를
 * DB 쓰기로 붙잡아 둘 이유가 없다. {@code spring.threads.virtual.enabled=true}이므로
 * 이 비동기 실행은 가상 스레드에서 일어난다(AsyncConfig 참고).
 *
 * <p><b>유실 가능성(실행 계획 1.8, BE-P1-7 — 문구 정정 2026-10-02)</b>: 비동기라 앱이 죽는 순간의 마지막 몇 건과
 * 저장에 실패한 건은 감사 스토어에 남지 않는다. <b>이를 보완하는 장치는 없다</b> — 예전 설명은 "Modulith 이벤트 발행
 * 로그(event_publication)가 보완한다"였지만 그 표는 쓰이지 않는다({@code @ApplicationModuleListener} 0건, 비어 있음 —
 * D-07). 감사 스토어는 사람이 보는 추적이고, 주문·체결의 최종 사실은 주문 테이블(orders)과 브로커(대사)에 있다.
 * 저장 실패는 카운터 {@code audit.write.failure{type}}로 센다(알림은 보류 — 계획 B4).
 *
 * <p><b>{@code @Transactional}</b>: 이벤트 스토어 append 경계를 명시적으로 고정한다
 * (Hibernate JDBC 배치가 같은 트랜잭션 안에서만 묶이므로, PLAN ADR-5의 batch_size 설정과
 * 짝을 이룬다).
 */
@Component
@Async
@Transactional
public class EventAuditListener {

    private static final Logger log = LoggerFactory.getLogger(EventAuditListener.class);

    /**
     * 이벤트 타입별 스키마 버전(실행 계획 1.8, BE-P2-12) — 각 이벤트 record 설명의 "(스키마 vN)"과 맞춘다.
     * 필드를 더해 버전을 올리면 여기도 올린다(EventAuditListenerTest가 감사 대상 타입이 모두 있는지 본다).
     * 과거 행은 그대로 둔다 — 2026-10-02 전에는 모든 행이 1로 기록됐다(Signal v2 포함).
     */
    static final Map<String, Integer> SCHEMA_VERSIONS = Map.of(
            "MarketTick", 1,
            "Signal", 2,            // fixedQuantity 추가(운영 1일차 ⑦)
            "SignalDecision", 1,
            "OrderRequest", 1,
            "Fill", 1,
            "MacroIndicator", 1,
            "NewsSentiment", 1);

    private final EventRecordRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public EventAuditListener(EventRecordRepository repository, ObjectMapper objectMapper, Clock clock,
                              MeterRegistry meterRegistry) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    @EventListener
    public void on(MarketTick e) { store("MarketTick", e, e.timestamp()); }

    @EventListener
    public void on(Signal e) { store("Signal", e, e.timestamp()); }

    @EventListener
    public void on(SignalDecision e) { store("SignalDecision", e, e.decidedAt()); }

    @EventListener
    public void on(OrderRequest e) { store("OrderRequest", e, e.timestamp()); }

    @EventListener
    public void on(Fill e) { store("Fill", e, e.timestamp()); }

    @EventListener
    public void on(MacroIndicator e) { store("MacroIndicator", e, e.timestamp()); }

    @EventListener
    public void on(NewsSentiment e) { store("NewsSentiment", e, e.timestamp()); }

    /**
     * 직렬화·저장 실패를 여기서 삼킨다(로그 후 skip). 예전에는 {@code IllegalStateException}을
     * 던졌는데, 이 메서드가 이제 {@code @Async}로 원래 호출 스레드와 분리돼 있어 예외를 던져도
     * 매매 흐름에는 영향이 없지만, 처리되지 않은 예외가 비동기 실행기 로그에 스택트레이스로만
     * 남고 조용히 사라지는 것보다는 여기서 명시적으로 잡아 error 레벨로 남기는 편이
     * 감시(모니터링)에 유리하다. 감사 기록 1건 실패가 시스템을 세울 이유는 없다는 원칙(PLAN
     * ADR-5) — 그 건은 감사 스토어에서 빠진다(클래스 설명 "유실 가능성").
     */
    private void store(String type, Object event, Instant occurredAt) {
        try {
            repository.save(new EventRecord(type, SCHEMA_VERSIONS.getOrDefault(type, 1),
                    objectMapper.writeValueAsString(event), occurredAt, clock.instant()));
        } catch (Exception ex) {
            meterRegistry.counter("audit.write.failure", "type", type).increment();
            log.error("이벤트 감사 기록 실패(스킵) — type={}, occurredAt={}", type, occurredAt, ex);
        }
    }
}

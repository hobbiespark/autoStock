package com.autostock.audit;

import com.autostock.common.event.Fill;
import com.autostock.common.event.MacroIndicator;
import com.autostock.common.event.MarketTick;
import com.autostock.common.event.NewsSentiment;
import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Signal;
import com.autostock.common.event.SignalDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 모든 도메인 이벤트를 이벤트 스토어에 기록. (스키마 v1 고정)
 * 주의: 고빈도 MarketTick은 추후 비동기 배치 flush로 전환 (PLAN 6절).
 *
 * <p><b>{@code @Async}(PLAN ADR-5)</b>: 감사 기록은 매매 판단에 필요한 값을 되돌려주지
 * 않는 "부수 효과"라서, 이벤트 발행 스레드(시그널→리스크→주문으로 이어지는 매매 핫패스)를
 * DB 쓰기로 붙잡아 둘 이유가 없다. {@code spring.threads.virtual.enabled=true}이므로
 * 이 비동기 실행은 가상 스레드에서 일어난다(AsyncConfig 참고). 다만 비동기이므로 앱이
 * 죽는 순간의 마지막 몇 건은 유실될 수 있는데, 이 유실 가능성은 Modulith의 이벤트 발행
 * 로그(트랜잭셔널 아웃박스, 원 이벤트 발행 자체는 동기·트랜잭션 내에서 보장됨)가 보완한다 —
 * 감사 스토어는 "사람이 보는 감사 추적"이고 최종 사실은 이벤트 발행 로그에 남는다.
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
     * ADR-5) — 실제 사실은 Modulith 이벤트 발행 로그에 남아 있으므로 재구성 가능하다.
     */
    private void store(String type, Object event, Instant occurredAt) {
        try {
            repository.save(new EventRecord(type, SCHEMA_V1, objectMapper.writeValueAsString(event), occurredAt));
        } catch (Exception ex) {
            log.error("이벤트 감사 기록 실패(스킵) — type={}, occurredAt={}", type, occurredAt, ex);
        }
    }
}

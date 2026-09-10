package com.autostock.monitor;

import com.autostock.common.event.SignalDecision;
import com.autostock.common.util.MarketConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * {@link SignalDecision} 이벤트를 {@code signal_decisions} 테이블에 영속화한다(FE-6,
 * PLAN.md ADR-10 확장표) — {@code audit.EventAuditListener}와 같은 이유로 {@code @Async}
 * (매매 핫패스를 DB 쓰기로 붙잡지 않음) + {@code @Transactional}(append 경계 명시) 패턴을
 * 그대로 따른다.
 *
 * <p>{@code trade_date}는 이벤트의 {@code decidedAt}(Instant)을 KST로 환산한 날짜다 —
 * C3LiveStrategy는 매 평일 09:05 KST에만 판단하므로 사실상 항상 당일 날짜가 되지만, 이
 * 환산을 이벤트 발행측(strategy/risk)이 아니라 저장측(monitor)에서 하는 이유는 "저장 시점의
 * 관례(트레이드 데이트 산출)"를 monitor 하나에만 두기 위해서다(daily_performance의
 * trade_date 산출과 같은 위치 원칙).
 */
@Component
@Async
@Transactional
public class SignalDecisionListener {

    private static final Logger log = LoggerFactory.getLogger(SignalDecisionListener.class);

    private final SignalDecisionRepository repository;
    private final ObjectMapper objectMapper;

    public SignalDecisionListener(SignalDecisionRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void on(SignalDecision event) {
        try {
            LocalDate tradeDate = LocalDate.ofInstant(event.decidedAt(), MarketConstants.KST);
            String metricsJson = objectMapper.writeValueAsString(event.metrics());
            repository.save(new SignalDecisionEntity(
                    event.decidedAt(), tradeDate, event.horizon(), event.strategyId(),
                    event.symbol(), event.conclusion(), event.reason(), metricsJson));
        } catch (Exception ex) {
            // EventAuditListener와 같은 원칙 — 감사성 저장 실패 1건이 시스템을 세울 이유는
            // 없다. 실제 사실은 EventAuditListener가 별도로 남기는 event_store에도 있으므로
            // 재구성 가능하다.
            log.error("SignalDecision 저장 실패(스킵) — symbol={}, decidedAt={}",
                    event.symbol(), event.decidedAt(), ex);
        }
    }
}

package com.autostock.strategy;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 전략 판단 상태 영속화 — 종목별 마지막 판단일({@code strategy_state}, V13, 실행 계획 1.1, aiDoc/c3-trading-day-cycle.md).
 *
 * <p>예외를 삼키지 않는다 — 실패했을 때 어떻게 할지는 호출자({@link C3LiveStrategy})가 정한다. 다른 모듈은 쓰지 않는다
 * (생성자 인자 타입이라 공개돼 있을 뿐이다 — {@code risk.RiskStateStore}와 같은 모양).
 */
@Component
public class StrategyStateStore {

    private final StrategyStateRepository repository;

    public StrategyStateStore(StrategyStateRepository repository) {
        this.repository = repository;
    }

    /** 이 전략의 종목별 마지막 판단일. 없으면 빈 맵. */
    @Transactional(readOnly = true)
    public Map<String, LocalDate> loadLastDecisionDates(String strategyId) {
        return repository.findByStrategyId(strategyId).stream()
                .collect(Collectors.toMap(StrategyStateEntity::getSymbol, StrategyStateEntity::getLastDecisionDate));
    }

    /** (전략, 종목)의 마지막 판단일을 갱신한다(없으면 만든다). 호출자의 트랜잭션과 묶이지 않는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveLastDecisionDate(String strategyId, String symbol, LocalDate date, Instant now) {
        StrategyStateEntity row = repository.findById(new StrategyStateEntity.Key(strategyId, symbol))
                .orElseGet(() -> new StrategyStateEntity(strategyId, symbol, now));
        row.decided(date, now);
        repository.save(row);
    }
}

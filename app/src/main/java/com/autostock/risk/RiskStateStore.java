package com.autostock.risk;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * risk 상태 영속화 — 킬스위치({@code risk_state})와 일별 실현손익 누계({@code risk_daily_pnl}), V9
 * (Phase 0.2, aiDoc/risk-state-persistence.md). 재기동(auto-start 포함)해도 비상 정지가 풀리거나 일 손실이
 * 0이 되지 않게 한다(사용자 결정 D-05).
 *
 * <p>예외를 삼키지 않는다 — 실패했을 때 어떻게 할지(fail-safe)는 호출자가 정한다({@link KillSwitch},
 * {@link DailyPnlTracker}). 다른 모듈은 쓰지 않는다(생성자 인자 타입이라 공개돼 있을 뿐이다).
 *
 * <p>저장은 {@code REQUIRES_NEW}다 — 호출자가 다른 트랜잭션 안에 있다가 롤백해도, 이미 바뀐 메모리 상태
 * (예: 작동한 킬스위치)와 DB가 어긋나지 않게 한다.
 */
@Component
public class RiskStateStore {

    private final RiskStateRepository stateRepository;
    private final RiskDailyPnlRepository dailyPnlRepository;

    public RiskStateStore(RiskStateRepository stateRepository, RiskDailyPnlRepository dailyPnlRepository) {
        this.stateRepository = stateRepository;
        this.dailyPnlRepository = dailyPnlRepository;
    }

    /** 저장된 킬스위치 상태. 행이 없으면 empty — 해제 상태로 본다. */
    public Optional<RiskStateEntity> loadKillSwitch() {
        return stateRepository.findById(RiskStateEntity.SINGLETON_ID);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveEngaged(String reason, Instant now) {
        RiskStateEntity state = singleton(now);
        state.engage(reason, now);
        stateRepository.save(state);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveReleased(String operator, Instant now) {
        RiskStateEntity state = singleton(now);
        state.release(operator, now);
        stateRepository.save(state);
    }

    private RiskStateEntity singleton(Instant now) {
        return stateRepository.findById(RiskStateEntity.SINGLETON_ID)
                .orElseGet(() -> RiskStateEntity.newSingleton(now));
    }

    /** 그 거래일(KST)의 저장된 실현손익 누계. */
    public Optional<BigDecimal> loadDailyPnl(LocalDate tradeDate) {
        return dailyPnlRepository.findById(tradeDate).map(RiskDailyPnlEntity::getRealizedPnl);
    }

    /** 그 거래일(KST) 행을 갱신한다(없으면 만든다). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveDailyPnl(LocalDate tradeDate, BigDecimal realizedPnl, Instant now) {
        RiskDailyPnlEntity row = dailyPnlRepository.findById(tradeDate)
                .orElseGet(() -> new RiskDailyPnlEntity(tradeDate));
        row.update(realizedPnl, now);
        dailyPnlRepository.save(row);
    }
}

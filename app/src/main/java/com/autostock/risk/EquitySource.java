package com.autostock.risk;

import java.math.BigDecimal;

/**
 * 계좌 평가액(equity) 조회 포트.
 *
 * <p>{@link RiskGate}(사이징 예산 계산)와 {@link DailyPnlTracker}(일 손실 한도 임계값 계산)가
 * 공용으로 의존한다. 구현체는 실행 모드(execution.mode)에 따라 조건부로 딱 하나만
 * 빈으로 등록된다 — SIM은 {@link PaperEquitySource}(설정값 고정), LIVE는
 * {@code execution.BrokerEquitySource}(브로커 잔고 조회, 캐시·폴백 포함)가 맡는다.
 * risk 모듈은 "지금 어떤 구현체가 떠 있는지" 전혀 몰라도 되게 하는 것이 이 인터페이스의 존재 이유다.
 */
public interface EquitySource {

    /**
     * 현재 계좌 평가액(KRW)을 반환한다.
     *
     * @return 계좌 평가액. 구현체는 조회 실패 시에도 예외를 던지지 않고 안전한 폴백값을
     *         반환해야 한다 — equity() 실패로 리스크 검사 전체가 멈추면 안 되기 때문이다.
     */
    BigDecimal equity();
}

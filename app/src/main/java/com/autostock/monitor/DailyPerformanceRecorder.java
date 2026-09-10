package com.autostock.monitor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 일별 성과 스냅샷 저장 Port(Hexagonal — ARCHITECTURE 4절 패턴, {@link Notifier}와 동일한
 * 이유). {@link DailyReportScheduler}는 "어떻게 저장되는지"를 전혀 모른 채 이 인터페이스만
 * 호출한다 — 단위 테스트에서 실제 DB/JPA 없이 이 인터페이스를 람다로 대체할 수 있게 하기
 * 위함이다(DailyReportSchedulerTest는 Spring 컨텍스트 없이 순수 객체 조립만 한다).
 */
public interface DailyPerformanceRecorder {

    /**
     * 그날의 집계 스냅샷을 upsert한다(trade_date 기준 있으면 갱신, 없으면 생성).
     *
     * @param tradeDate          거래일(KST 기준)
     * @param realizedPnl        당일 실현손익
     * @param orderCount         당일 주문 수
     * @param fillCount          당일 체결 건수
     * @param avgSlippageBps     당일 평균 슬리피지(bps, 양수=불리)
     * @param maxSlippageBps     당일 최대 슬리피지(bps)
     * @param conservativeMode   보수 모드(거시 국면) 여부
     * @param killSwitchEngaged  킬스위치 작동 여부
     */
    void saveSnapshot(LocalDate tradeDate, BigDecimal realizedPnl, int orderCount, int fillCount,
                      double avgSlippageBps, double maxSlippageBps,
                      boolean conservativeMode, boolean killSwitchEngaged);
}

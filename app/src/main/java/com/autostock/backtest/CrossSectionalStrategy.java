package com.autostock.backtest;

import java.util.List;
import java.util.Map;

/**
 * 횡단면(cross-sectional) 전략 — 형성 시점마다 유니버스 안의 종목을 순위 매겨 보유 집합을 고른다.
 *
 * <p>기존 {@link BacktestStrategy}(단일 종목 시계열 규칙)와 다른 종류다. 시계열 전략은 "이 종목을
 * 지금 살까"를 묻고, 횡단면 전략은 "이 200종목 중 어느 20종목을 들고 갈까"를 묻는다.
 * docs/research/gate1_diagnosis_20260918.md 1절 — 17계열 전부 FAIL의 구조적 원인(표본 크기·강세장 노출 벌점)을
 * 피하기 위해 도입한 전략 종류이며, 판정 도구(부트스트랩·SPA)는 시계열 계열과 동일하게 적용한다.
 *
 * <p>구현은 상태를 갖지 않아야 한다(형성 시점마다 독립 호출). 룩어헤드 방지는 엔진이 책임진다 —
 * {@code histories}에는 형성일 종가까지의 데이터만 들어오고, 체결은 익일 시가다.
 */
public interface CrossSectionalStrategy {

    /** 결과 표에 찍히는 계열 라벨(예: "X1 12-1 모멘텀 top20"). */
    String label();

    /** 순위 계산에 필요한 최소 거래일 수 — 이보다 짧은 종목은 엔진이 후보에서 제외한다. */
    int minHistoryDays();

    /**
     * 유니버스에서 보유할 종목을 고른다.
     *
     * @param histories 형성일까지의 종가 시계열(오래된 순, 길이 ≥ {@link #minHistoryDays()}), 유니버스 종목만
     * @return 보유 종목 코드(동일가중). 비어 있으면 그 기간은 전액 현금
     */
    List<String> select(Map<String, double[]> histories);
}

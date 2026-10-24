package com.autostock.monitor.view;

import java.math.BigDecimal;

/**
 * 일 손실 한도 View DTO(2026-10-23) — 대시보드의 예전 고정 문구("일 손실 한도는 -2%를 참고 기준") 대신 지금 쓰는 값.
 * risk.DailyPnlTracker.LimitStatus를 그대로 옮겨 담는다.
 *
 * @param lossPct        한도 비율(음수, 예: -0.05 — 모의 프로필)
 * @param base           한도 기준 금액(원) — 운용 자금이 켜져 있으면 min(계좌, 운용 자금)과 오늘 최대 보유액 중 큰 값. 모르면 null
 * @param threshold      한도 금액(원, 음수) — 오늘 한도용 손익이 이 값 이하가 되면 킬스위치. 모르면 null
 * @param pnlForLimit    오늘 한도용 손익(밤사이 보유분은 전일 종가 기준)
 * @param capitalLimited 운용 자금 한도가 켜져 있는가
 * @param capitalLimit   운용 자금(원) — 꺼져 있으면 null
 * @param peakHeld       오늘 최대 보유액(기준가 기준, 원)
 */
public record DailyLossLimitView(double lossPct, BigDecimal base, BigDecimal threshold, BigDecimal pnlForLimit,
                                 boolean capitalLimited, BigDecimal capitalLimit, BigDecimal peakHeld) {
}

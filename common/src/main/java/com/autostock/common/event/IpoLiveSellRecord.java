package com.autostock.common.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 공모주 상장일 실거래 매도 기록(스키마 v1, 2026-11-05, aiDoc/ipo-automation.md 8절).
 *
 * <p>ipo 모듈이 계획 안내·확인·주문·체결·거부·결과 불명·중단을 할 때마다 발행한다. monitor 리스너가 텔레그램으로 보내고
 * ({@code urgent}면 WARN), audit 리스너가 event_store에 남긴다. 키·계좌 정보는 담지 않는다.
 *
 * @param corpName 회사 이름(여러 회사 묶음 안내면 "공모주 상장일 매도")
 * @param symbol   종목코드(없으면 null)
 * @param action   PLAN·CONFIRM·ORDER·FILL·SKIP·OFF·SUMMARY 중 하나
 * @param tranche  FIRST·SECOND·THIRD·CLOSE 또는 null
 * @param quantity 주문 수량(없으면 0)
 * @param price    지정가(없으면 null)
 * @param orderNo  증권사 주문번호(접수 때만)
 * @param outcome  ACCEPTED·REJECTED·UNKNOWN·FILLED 또는 null
 * @param message  사람이 읽는 한국어 본문
 * @param urgent   사람이 바로 봐야 하는가(거부·결과 불명·중단)
 * @param at       발생 시각
 */
public record IpoLiveSellRecord(
        String corpName,
        String symbol,
        String action,
        String tranche,
        long quantity,
        BigDecimal price,
        String orderNo,
        String outcome,
        String message,
        boolean urgent,
        Instant at
) {
}

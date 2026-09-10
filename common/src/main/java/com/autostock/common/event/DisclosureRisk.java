package com.autostock.common.event;

import java.time.Instant;
import java.time.LocalDate;

/**
 * DART 주요사항보고서 기반 리스크 공시 감지 이벤트 (PLAN.md ADR-14, 트랙 G1).
 *
 * <p>macrointel 모듈이 유상증자·CB·BW·EB 발행 결정 공시를 감지하면 이 이벤트만 발행한다 —
 * "어떤 종목을 얼마나 차단할지" 판단(risk.DisclosureBlacklist)은 전혀 모른다. risk 모듈이
 * 이 이벤트를 구독해 블랙리스트에 반영한다(macrointel → risk 타입 의존 없이 이벤트로만 통신,
 * {@code macrointel.package-info.java}·{@code IpoAlert}와 같은 브리지 패턴).
 *
 * @param symbol          종목코드(stock_code) — 공백/비상장이면 애초에 발행되지 않는다
 *                        (macrointel.DisclosureBlacklistSyncScheduler가 필터링)
 * @param corpName        회사명
 * @param disclosureType  공시 유형 — {@code macrointel.DisclosureType} enum name 문자열
 *                        (PAID_IN_CAPITAL_INCREASE/CONVERTIBLE_BOND/BOND_WITH_WARRANT/EXCHANGEABLE_BOND).
 *                        common은 macrointel enum 타입을 참조하지 않도록 문자열로만 옮겨 담는다.
 * @param rceptNo         DART 접수번호 — (symbol, rceptNo) 조합이 risk 쪽 멱등키(중복 등록 방지)
 * @param rceptDt         공시 접수일
 * @param expiresOn       블랙리스트 만료일(공시일 + 보유 기간, ADR-14 "6~24개월 회피" 하한 180일)
 * @param at              이벤트 발행 시각
 */
public record DisclosureRisk(
        String symbol,
        String corpName,
        String disclosureType,
        String rceptNo,
        LocalDate rceptDt,
        LocalDate expiresOn,
        Instant at
) {
}

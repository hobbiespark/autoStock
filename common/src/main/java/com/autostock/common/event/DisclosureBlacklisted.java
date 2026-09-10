package com.autostock.common.event;

import java.time.Instant;
import java.time.LocalDate;

/**
 * risk.DisclosureBlacklist가 새 종목을 실제로 등록했을 때 발행하는 알림 이벤트
 * (PLAN.md ADR-14, 트랙 G1) — {@code KillSwitchChanged}와 같은 패턴: risk 모듈은 "누가
 * 알림을 받는지" 전혀 모른 채 상태 변화만 알리고, monitor 모듈의 리스너가 Notifier(텔레그램)로
 * 이어준다.
 *
 * <p>{@code DisclosureRisk} 이벤트가 들어올 때마다 매번 발행되지 않는다 — 이미 같은
 * (symbol, rceptNo) 조합이 등록돼 있으면(재처리·스케줄러 재실행) 중복 발행하지 않고 조용히
 * 무시한다(risk.DisclosureBlacklist 멱등 처리).
 *
 * @param symbol         종목코드
 * @param corpName       회사명
 * @param disclosureType 공시 유형(사람이 읽을 한국어 라벨 — 예: "유상증자 결정")
 * @param rceptNo        DART 접수번호
 * @param expiresOn      블랙리스트 만료일
 * @param at             등록 시각
 */
public record DisclosureBlacklisted(
        String symbol,
        String corpName,
        String disclosureType,
        String rceptNo,
        LocalDate expiresOn,
        Instant at
) {
}

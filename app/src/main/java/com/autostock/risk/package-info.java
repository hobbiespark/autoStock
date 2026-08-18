/**
 * 리스크 모듈 — 주문의 유일한 관문 (PLAN 4절 불변 원칙).
 * Signal은 여기서 사이징·한도·킬스위치 검사를 통과해야만 OrderRequest가 된다.
 *
 * <p>모듈 간 의존(ADR-6 재편): {@link com.autostock.risk.RiskGate}가 사이징·매도 수량
 * 판단을 위해 portfolio 모듈의 {@code PositionBook}을 조회한다(risk → portfolio 단방향,
 * portfolio는 risk를 참조하지 않으므로 순환 없음). market 모듈의 {@code MarketCalendarService}
 * (장 시간 판정)도 같은 방식으로 참조한다. 반면 {@code DailyPnlTracker}·{@code KillSwitch}·
 * {@code EquitySource} 같은 리스크 한도 장치는 portfolio로 옮기지 않고 그대로 risk에
 * 둔다 — "얼마나 위험한지 판단하고 차단하는" 책임은 risk 고유의 것이기 때문이다.
 *
 * <p><b>macro-intel 경계(PLAN 5절, Phase 5)</b>: {@link com.autostock.risk.MacroGuard}(거시
 * 국면 판정 → 보수 모드/킬스위치)와 {@link com.autostock.risk.DisclosureBlacklist}(공시 기반
 * 매수 배제)는 macrointel 모듈이 아니라 risk에 둔다 — 위 원칙("판단·차단은 risk 고유 책임")을
 * 그대로 적용한 결과다. macrointel은 FRED/ECOS 수집과 {@code MacroIndicator} 이벤트 발행까지만
 * 담당하고, 그 값을 해석해 매매를 제한하는 판단은 하지 않는다(macrointel/package-info.java
 * 참고). MacroGuard는 임계치 설정({@code macrointel.MacroIntelProperties})만 macrointel에서
 * 참조한다(risk → macrointel 단방향, macrointel은 risk를 모른다 — 순환 없음).
 */
@org.springframework.modulith.ApplicationModule(displayName = "risk")
package com.autostock.risk;

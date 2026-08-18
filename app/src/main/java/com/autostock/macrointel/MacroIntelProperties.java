package com.autostock.macrointel;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 거시 인텔리전스 수집·판정 설정 (PLAN 5절 1단계 — 규칙 기반).
 *
 * <p>{@code enabled} 기본값이 false인 이유: {@code HolidayApiProperties}·{@code KiwoomProperties}와
 * 같은 이유다 — FRED_API_KEY/ECOS_API_KEY를 아직 발급받아 실제 응답 포맷을 실측하지 못했다.
 * 운영자가 키를 발급·실측한 뒤 {@code macrointel.enabled=true}로 명시적으로 켜야 한다
 * ({@link MacroSyncScheduler} 클래스 설명 참고).
 *
 * <p><b>임계치를 이 클래스 하나에 두는 이유</b>: {@code vixCautionThreshold}·
 * {@code vixSevereThreshold}·{@code usdKrwCautionThreshold}는 수집 배치({@link MacroSyncScheduler})가
 * 아니라 위험 판단({@code risk.MacroGuard})이 실제로 사용하는 값이다. 그렇다고 risk 모듈에
 * 따로 복제해 두면 두 곳의 값이 서서히 어긋나는(drift) 사고가 날 수 있어, 값의 "소유"는
 * 이 클래스(수집 설정과 같은 자리) 하나로 유지하고 risk가 이를 그대로 참조한다.
 * risk → macrointel 참조는 risk → market.MarketCalendarService, risk → portfolio.PositionBook과
 * 같은 성격의 허용된 모듈 간 참조다(둘 다 상대 모듈이 참조하지 않는 단방향이라 순환이
 * 생기지 않는다 — {@code ModularityTests}로 검증).
 *
 * @param enabled                수집 배치 활성화 여부(기본 false)
 * @param fredApiKey              FRED API 키. 커밋 금지 — 환경변수 FRED_API_KEY로만 주입
 * @param ecosApiKey              ECOS API 키. 커밋 금지 — 환경변수 ECOS_API_KEY로만 주입
 * @param vixCautionThreshold     VIX 보수 모드 진입 임계치(기본 25.0)
 * @param vixSevereThreshold      VIX 킬스위치 작동 임계치(기본 35.0) — 보수모드보다 높은 극단 국면
 * @param usdKrwCautionThreshold  원/달러 환율 보수 모드 진입 임계치(기본 1450.0)
 */
@ConfigurationProperties(prefix = "macrointel")
public record MacroIntelProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String fredApiKey,
        @DefaultValue("") String ecosApiKey,
        @DefaultValue("25.0") double vixCautionThreshold,
        @DefaultValue("35.0") double vixSevereThreshold,
        @DefaultValue("1450.0") double usdKrwCautionThreshold
) {
}

package com.autostock.market;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 공공데이터포털 "특일 정보"(한국천문연구원 SpcdeInfoService) 연동 설정.
 *
 * <p>{@code enabled} 기본값이 false인 이유: KiwoomProperties.appKey나 monitor.telegram과
 * 같은 이유다 — 서비스키를 아직 발급받아 실제 응답 포맷(JSON 지원 여부 등)을 실측하지
 * 못했다. 운영자가 발급·실측 후 {@code market.holiday-api.enabled=true}로 명시적으로
 * 켜야 한다({@link HolidaySyncService} 클래스 설명 참고).
 *
 * @param enabled     특일 API 연동 활성화 여부 (기본 false — 서비스키 발급·실측 전)
 * @param serviceKey  공공데이터포털 발급 서비스키. 커밋 금지 — 환경변수 DATA_GO_KR_SERVICE_KEY로만 주입
 * @param baseUrl     특일 정보 서비스 기본 URL
 */
@ConfigurationProperties(prefix = "market.holiday-api")
public record HolidayApiProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String serviceKey,
        @DefaultValue("https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService") String baseUrl
) {
}

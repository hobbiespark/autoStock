package com.autostock.macrointel;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * DART 주요사항(유상증자·CB·BW·EB) 감시 배치 설정 (PLAN.md ADR-14, 트랙 G1).
 *
 * <p>{@code dartApiKey}는 {@code ipo.DartProperties.apiKey}와 같은 환경변수(DART_API_KEY)를
 * 그대로 재사용한다 — 같은 발급 키를 쓰는 것뿐이고, 클래스 자체는 macrointel이 ipo 모듈의
 * {@code DartProperties} 타입을 참조하지 않도록 독립적으로 둔다(모듈 경계 원칙, macrointel과
 * ipo 사이에 참조가 생기면 안 됨 — ModularityTests). {@code enabled} 기본값이 false인 이유는
 * {@code ipo.DartProperties}·{@code macrointel.MacroIntelProperties}와 같다.
 *
 * @param enabled       배치 활성화 여부(기본 false)
 * @param dartApiKey    OpenDART 인증키. 커밋 금지 — 환경변수 DART_API_KEY로만 주입
 * @param lookbackDays  list.json 조회 시 "최근 N일" 창(기본 7일 — 평일 매일 08:35 실행이라
 *                      주말·공휴일 연휴를 감안해도 7일이면 누락 없이 커버된다)
 * @param retentionDays 블랙리스트 등록 기간(공시일로부터, 기본 180일) — ADR-14 G1 근거
 *                       (Loughran &amp; Ritter 1995)의 "SEO 장기 저성과는 6~24개월 지속"
 *                       구간의 하한을 보수적으로 채택한 값. 추후 백테스트로 상향 검토 가능.
 */
@ConfigurationProperties(prefix = "macrointel.blacklist")
public record DisclosureBlacklistProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String dartApiKey,
        @DefaultValue("7") int lookbackDays,
        @DefaultValue("180") int retentionDays
) {
}

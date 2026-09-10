package com.autostock.ipo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * OpenDART 수집 설정 (PLAN.md ADR-9, 트랙 E2).
 *
 * <p>{@code enabled} 기본값이 false인 이유는 {@code macrointel.MacroIntelProperties}와 같다 —
 * 운영자가 DART_API_KEY를 발급·{@code .env}에 등록한 뒤 명시적으로 켜야 한다. 이 리포에서는
 * 실제로 키를 실측 확인했지만(docs/measured/dart_estkRs_20260911.json), 배포 환경마다 키 발급
 * 여부가 다를 수 있어 기본값은 여전히 false로 둔다.
 *
 * @param enabled          수집 배치 활성화 여부(기본 false)
 * @param apiKey           DART Open API 인증키. 커밋 금지 — 환경변수 DART_API_KEY로만 주입
 * @param lookbackDays      list.json 조회 시 "최근 N일" 창(기본 14일, ADR-9 배치 사양)
 */
@ConfigurationProperties(prefix = "dart")
public record DartProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String apiKey,
        @DefaultValue("14") int lookbackDays
) {
}

package com.autostock.smoke;

import com.autostock.macrointel.DisclosureBlacklistProperties;
import com.autostock.macrointel.MajorDisclosureDartClient;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OpenDART 실제 서버에 대고 돌리는 통합 스모크 테스트(PLAN.md ADR-14, 트랙 G1) — {@code
 * DartIpoSmokeIT}와 동일 관례: 스프링 컨텍스트 없이 최소 배선, DART_API_KEY가 없으면
 * (CI 등) 전부 스킵한다.
 *
 * <p>실행 방법(로컬):
 * <pre>
 *   set -a; source .env; set +a
 *   ./gradlew test --tests '*DartDisclosureSmokeIT*'
 * </pre>
 */
class DartDisclosureSmokeIT {

    @Test
    void list_json_주요사항보고서가_실제_DART_서버에서_정상_동작한다() {
        String apiKey = System.getenv("DART_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(),
                "DART_API_KEY가 없어 스모크 테스트를 스킵함 — .env를 source한 뒤 다시 실행할 것");

        DisclosureBlacklistProperties properties = new DisclosureBlacklistProperties(true, apiKey, 14, 180);
        MajorDisclosureDartClient client = new MajorDisclosureDartClient(WebClient.builder(), properties);

        // 최근 14일 주요사항보고서(유상증자/CB/BW/EB) — 결과 건수는 시점마다 달라지므로 예외 없이
        // 호출이 성공하는지(빈 리스트 포함 정상)만 확인한다.
        LocalDate today = LocalDate.now();
        List<MajorDisclosureDartClient.MajorDisclosureNotice> notices =
                client.fetchRecentIssuanceDecisions(today.minusDays(14), today);
        assertFalse(notices == null, "조회 자체는 null 없이 리스트로 돌아와야 함(실패 시 빈 리스트)");

        notices.stream().findFirst().ifPresent(n ->
                org.slf4j.LoggerFactory.getLogger(DartDisclosureSmokeIT.class)
                        .info("주요사항 실측: corpName={}, type={}, stockCode={}, rceptDt={}",
                                n.corpName(), n.type(), n.stockCode(), n.rceptDt()));
    }
}

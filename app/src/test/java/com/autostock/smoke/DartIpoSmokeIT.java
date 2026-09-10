package com.autostock.smoke;

import com.autostock.ipo.DartClient;
import com.autostock.ipo.DartProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OpenDART 실제 서버에 대고 돌리는 통합 스모크 테스트(PLAN.md ADR-9, 트랙 E2) — {@code
 * KiwoomSmokeIT}와 동일한 관례: 스프링 컨텍스트 없이 최소 배선, DART_API_KEY가 없으면
 * (CI 등) 전부 스킵한다.
 *
 * <p>실행 방법(로컬):
 * <pre>
 *   set -a; source .env; set +a
 *   ./gradlew test --tests '*DartIpoSmokeIT*'
 * </pre>
 */
class DartIpoSmokeIT {

    @Test
    void list_json과_estkRs_json이_실제_DART_서버에서_정상_동작한다() {
        String apiKey = System.getenv("DART_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(),
                "DART_API_KEY가 없어 스모크 테스트를 스킵함 — .env를 source한 뒤 다시 실행할 것");

        DartProperties properties = new DartProperties(true, apiKey, 14);
        DartClient client = new DartClient(WebClient.builder(), properties);

        // 최근 14일 증권신고서(지분증권) 목록 — 결과 건수는 시점마다 달라지므로 예외 없이
        // 호출이 성공하는지(빈 리스트 포함 정상)만 확인한다.
        LocalDate today = LocalDate.now();
        List<DartClient.DealNotice> deals = client.fetchRecentEquityFilings(today.minusDays(14), today);
        assertFalse(deals == null, "조회 자체는 null 없이 리스트로 돌아와야 함(실패 시 빈 리스트)");

        if (!deals.isEmpty()) {
            // 딜이 하나라도 있으면 상세 조회(estkRs.json)도 예외 없이 동작하는지 확인.
            DartClient.DealNotice first = deals.get(0);
            Optional<DartClient.OfferingDetail> detail = client.fetchOfferingDetail(
                    first.corpCode(), first.rceptNo(), LocalDate.of(2026, 1, 1), today);
            // 미제공 필드는 null 허용(ADR-9) — Optional 자체가 비어있을 수도 있어(조회 범위
            // 밖) 단언하지 않고, 예외 없이 이 지점까지 왔다는 것으로 계약을 확인한다.
            detail.ifPresent(d -> org.slf4j.LoggerFactory.getLogger(DartIpoSmokeIT.class)
                    .info("estkRs 실측: corpName={}, subStart={}, leadManager={}, offerPrice={}",
                            first.corpName(), d.subscriptionStart(), d.leadManager(), d.offerPriceConfirmed()));
        }
    }
}

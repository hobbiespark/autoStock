package com.autostock.smoke;

import com.autostock.common.event.Candle;
import com.autostock.kiwoom.KiwoomProperties;
import com.autostock.kiwoom.KiwoomRestClient;
import com.autostock.kiwoom.TokenManager;
import com.autostock.kiwoom.TrId;
import com.autostock.kiwoom.TrRateLimiter;
import com.autostock.market.KiwoomDailyChartService;
import com.autostock.market.MarketQueryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 키움 모의투자(mockapi.kiwoom.com) 실제 서버에 대고 돌리는 통합 스모크 테스트.
 *
 * <p>스프링 컨텍스트를 띄우지 않고(DB/Flyway 등 무관한 의존성을 피하려고) 각 컴포넌트를
 * 직접 조립한다 — 이 프로젝트에는 아직 {@code @SpringBootTest} 통합 테스트 인프라가 없고,
 * 이 테스트의 목적은 "실제 키움 서버가 문서/코드가 가정한 대로 응답하는가"만 확인하는 것이라
 * 최소 배선으로 충분하다.
 *
 * <p><b>실행 조건</b>: 환경변수 {@code KIWOOM_APP_KEY}/{@code KIWOOM_APP_SECRET}가 없으면
 * (CI, 키 미보유 개발자 환경 등) 전부 스킵한다 — 실제 네트워크 호출이 필요한 테스트를
 * 빌드 필수 경로에 두면 안 되기 때문이다.
 *
 * <p>실행 방법(로컬):
 * <pre>
 *   set -a; source .env; set +a
 *   ./gradlew test --tests '*KiwoomSmokeIT*'
 * </pre>
 */
class KiwoomSmokeIT {

    /** 모의투자 base URL — 실전 URL과 절대 혼동되면 안 되므로 여기 하드코딩해 명시적으로 고정한다. */
    private static final String PAPER_BASE_URL = "https://mockapi.kiwoom.com";
    private static final String PAPER_WS_URL = "wss://mockapi.kiwoom.com:10000/api/dostk/websocket";

    /** 신명(primaryEnv) 우선, 없으면 구명(fallbackEnv)으로 폴백해 환경변수를 읽는다. */
    private static String envOrFallback(String primaryEnv, String fallbackEnv) {
        String primary = System.getenv(primaryEnv);
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return System.getenv(fallbackEnv);
    }

    @Test
    void 토큰_발급_잔고조회_일봉조회가_실제_모의투자_서버에서_정상_동작한다() {
        // KIWOOM_MOCK_G_*(신명) 우선, 없으면 구명 KIWOOM_APP_*로 폴백(build.gradle의 test env 주입과 동일 관례).
        // 실전(LIVE_*)은 이 스모크 테스트에서 절대 사용하지 않는다 — 모의투자 서버 전용.
        String appKey = envOrFallback("KIWOOM_MOCK_G_APP_KEY", "KIWOOM_APP_KEY");
        String appSecret = envOrFallback("KIWOOM_MOCK_G_APP_SECRET", "KIWOOM_APP_SECRET");
        assumeTrue(appKey != null && !appKey.isBlank() && appSecret != null && !appSecret.isBlank(),
                "KIWOOM_MOCK_G_APP_KEY/SECRET(또는 구명 KIWOOM_APP_KEY/SECRET)이 없어 스모크 테스트를 스킵함 — "
                        + ".env를 source한 뒤 다시 실행할 것");

        KiwoomProperties properties = new KiwoomProperties(PAPER_BASE_URL, PAPER_WS_URL, appKey, appSecret);
        WebClient.Builder builder = WebClient.builder();

        // ── 1) 토큰 발급 ──────────────────────────────────────────────────────
        TokenManager tokenManager = new TokenManager(builder, properties);
        String token = tokenManager.accessToken();
        assertFalse(token == null || token.isBlank(), "접근토큰이 발급되어야 함");

        // ── 2) 잔고 조회(kt00018) — return_code==0 검증 ──────────────────────
        TrRateLimiter rateLimiter = new TrRateLimiter();
        KiwoomRestClient restClient = new KiwoomRestClient(
                builder, properties, tokenManager, rateLimiter, new SimpleMeterRegistry());
        Map<String, Object> balance = restClient.call(TrId.ACCOUNT_BALANCE, "/api/dostk/acnt",
                Map.of("qry_tp", "1", "dmst_stex_tp", "KRX"));
        assertEquals(0, ((Number) balance.get("return_code")).intValue(),
                "잔고 조회 return_code는 0(정상)이어야 함: " + balance.get("return_msg"));

        // ── 3) 일봉 조회(ka10081) — 20봉 이상, 시간순 정렬 검증 ────────────────
        MarketQueryService marketQueryService = new MarketQueryService(restClient);
        KiwoomDailyChartService dailyChartService = new KiwoomDailyChartService(marketQueryService);
        LocalDate baseDate = LocalDate.now(ZoneId.of("Asia/Seoul"));

        List<Candle> candles = dailyChartService.fetchDaily("005930", baseDate, 20);

        assertTrue(candles.size() >= 20,
                "삼성전자(005930) 일봉이 20개 이상 로드되어야 함, 실제 " + candles.size() + "개");
        for (int i = 1; i < candles.size(); i++) {
            assertFalse(candles.get(i).date().isBefore(candles.get(i - 1).date()),
                    "일봉은 날짜 오름차순으로 정렬돼 있어야 함");
        }
    }
}

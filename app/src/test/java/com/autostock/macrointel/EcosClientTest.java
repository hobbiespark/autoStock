package com.autostock.macrointel;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EcosClient 응답 파싱 검증 — {@link EcosClient#callApi} 오버라이드 패턴
 * ({@code market.HolidaySyncServiceTest}와 같은 방식).
 */
class EcosClientTest {

    private static final MacroIntelProperties PROPERTIES =
            new MacroIntelProperties(true, "", "test-ecos-key", 25.0, 35.0, 1450.0);

    @Test
    void 정상_응답에서_최신값을_파싱한다() {
        FakeEcosClient client = new FakeEcosClient();
        client.stub(Map.of("StatisticSearch", Map.of("row",
                List.of(Map.of("TIME", "20260817", "DATA_VALUE", "1345.50")))));

        Optional<EcosClient.Observation> result = client.fetchLatest(EcosClient.STAT_CODE_USDKRW);

        assertTrue(result.isPresent());
        assertEquals(LocalDate.of(2026, 8, 17), result.get().date());
        assertEquals(new BigDecimal("1345.50"), result.get().value());
    }

    @Test
    void row가_단일_객체로_와도_파싱한다() {
        // 공공 API 계열에서 결과가 1건이면 배열이 아니라 단일 객체로 오는 경우 방어(클래스 설명 참고).
        FakeEcosClient client = new FakeEcosClient();
        client.stub(Map.of("StatisticSearch", Map.of("row",
                Map.of("TIME", "20260817", "DATA_VALUE", "3.50"))));

        Optional<EcosClient.Observation> result = client.fetchLatest(EcosClient.STAT_CODE_BASE_RATE);

        assertTrue(result.isPresent());
        assertEquals(new BigDecimal("3.50"), result.get().value());
    }

    @Test
    void StatisticSearch가_없으면_빈_결과를_반환한다() {
        FakeEcosClient client = new FakeEcosClient();
        client.stub(Map.of("RESULT", Map.of("CODE", "INFO-200")));

        assertTrue(client.fetchLatest(EcosClient.STAT_CODE_USDKRW).isEmpty());
    }

    @Test
    void API_호출이_예외를_던져도_예외를_밖으로_전파하지_않는다() {
        FakeEcosClient client = new FakeEcosClient();
        client.failNext();

        assertTrue(client.fetchLatest(EcosClient.STAT_CODE_USDKRW).isEmpty());
    }

    /** 실제 HTTP 호출 없이 준비된 응답 Map으로 대체하는 테스트 전용 서브클래스. */
    private static final class FakeEcosClient extends EcosClient {
        private Map<String, Object> stubbedResponse;
        private boolean shouldFail;

        FakeEcosClient() {
            super(WebClient.builder(), PROPERTIES);
        }

        void stub(Map<String, Object> response) {
            this.stubbedResponse = response;
        }

        void failNext() {
            this.shouldFail = true;
        }

        @Override
        protected Map<String, Object> callApi(String statCode, LocalDate date) {
            if (shouldFail) {
                throw new RuntimeException("테스트 강제 실패");
            }
            return stubbedResponse;
        }
    }
}

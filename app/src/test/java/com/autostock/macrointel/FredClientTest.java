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
 * FredClient 응답 파싱 검증 — 실제 WebClient 호출 없이 {@link FredClient#callApi}를
 * 오버라이드해 준비된 응답으로 대체한다({@code market.HolidaySyncServiceTest}와 같은 패턴).
 */
class FredClientTest {

    private static final MacroIntelProperties PROPERTIES =
            new MacroIntelProperties(true, "test-fred-key", "", 25.0, 35.0, 1450.0);

    @Test
    void 정상_응답에서_최신_관측치를_파싱한다() {
        FakeFredClient client = new FakeFredClient();
        client.stub(FredClient.SERIES_VIX, Map.of("observations",
                List.of(Map.of("date", "2026-08-17", "value", "18.52"))));

        Optional<FredClient.Observation> result = client.fetchLatest(FredClient.SERIES_VIX);

        assertTrue(result.isPresent());
        assertEquals(LocalDate.of(2026, 8, 17), result.get().date());
        assertEquals(new BigDecimal("18.52"), result.get().value());
    }

    @Test
    void 결측치_점_표기는_빈_결과로_처리한다() {
        FakeFredClient client = new FakeFredClient();
        client.stub(FredClient.SERIES_VIX, Map.of("observations",
                List.of(Map.of("date", "2026-08-17", "value", "."))));

        assertTrue(client.fetchLatest(FredClient.SERIES_VIX).isEmpty());
    }

    @Test
    void observations가_비어있으면_빈_결과를_반환한다() {
        FakeFredClient client = new FakeFredClient();
        client.stub(FredClient.SERIES_VIX, Map.of("observations", List.of()));

        assertTrue(client.fetchLatest(FredClient.SERIES_VIX).isEmpty());
    }

    @Test
    void API_호출이_예외를_던져도_예외를_밖으로_전파하지_않는다() {
        FakeFredClient client = new FakeFredClient();
        client.failNext();

        assertTrue(client.fetchLatest(FredClient.SERIES_VIX).isEmpty());
    }

    @Test
    void 예상과_다른_응답_포맷은_빈_결과로_처리한다() {
        FakeFredClient client = new FakeFredClient();
        client.stub(FredClient.SERIES_VIX, Map.of("unexpected", "shape"));

        assertTrue(client.fetchLatest(FredClient.SERIES_VIX).isEmpty());
    }

    /** 실제 HTTP 호출 없이 준비된 응답 Map으로 대체하는 테스트 전용 서브클래스. */
    private static final class FakeFredClient extends FredClient {
        private Map<String, Object> stubbedResponse;
        private boolean shouldFail;

        FakeFredClient() {
            super(WebClient.builder(), PROPERTIES);
        }

        void stub(String seriesId, Map<String, Object> response) {
            this.stubbedResponse = response;
        }

        void failNext() {
            this.shouldFail = true;
        }

        @Override
        protected Map<String, Object> callApi(String seriesId) {
            if (shouldFail) {
                throw new RuntimeException("테스트 강제 실패");
            }
            return stubbedResponse;
        }
    }
}

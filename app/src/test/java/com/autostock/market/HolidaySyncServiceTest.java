package com.autostock.market;

import com.autostock.common.util.MarketConstants;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * HolidaySyncService 검증 — 실제 WebClient 호출 없이 {@link HolidaySyncService#callApi}를
 * 오버라이드해 준비된 응답으로 대체한다({@code strategy.C3LiveStrategyTest}의 StubChartService와
 * 같은 패턴, HolidaySyncService 클래스 설명 "callApi" 절 참고). 응답 파싱→저장, isHoliday=N
 * 제외, 월별 부분 실패 시 기존 데이터 보존, enabled=false no-op을 검증한다.
 */
class HolidaySyncServiceTest {

    @Test
    void isHoliday가_Y인_항목만_저장하고_N은_제외한다() {
        FakeHolidaySyncService sync = new FakeHolidaySyncService(true);
        sync.stub(2027, 1, responseWithItems(
                item("20270101", "신정", "Y"),
                item("20270105", "그냥_기념일", "N")));

        MarketHolidayRepository repository = sync.repository;
        when(repository.findById(any())).thenReturn(Optional.empty());

        sync.syncYear(2027);

        // isHoliday=Y인 20270101만 저장돼야 한다 — N인 20270105는 저장 호출 자체가 없어야 함.
        verify(repository, times(1)).save(any());
        verify(repository).findById(LocalDate.of(2027, 1, 1));
        verify(repository, never()).findById(LocalDate.of(2027, 1, 5));
    }

    @Test
    void 이미_존재하는_날짜는_갱신하고_없으면_새로_생성한다() {
        FakeHolidaySyncService sync = new FakeHolidaySyncService(true);
        sync.stub(2027, 3, responseWithItems(item("20270301", "삼일절", "Y")));

        MarketHolidayEntity existing = new MarketHolidayEntity(
                LocalDate.of(2027, 3, 1), "구이름", "DATA_GO_KR", false, Instant.EPOCH);
        MarketHolidayRepository repository = sync.repository;
        when(repository.findById(LocalDate.of(2027, 3, 1))).thenReturn(Optional.of(existing));

        sync.syncYear(2027);

        verify(repository, times(1)).save(existing);
        assertEquals("삼일절", existing.getName(), "기존 엔티티의 이름이 갱신돼야 함");
    }

    @Test
    void 특정_달_호출이_실패해도_다른_달의_저장_결과는_유지된다() {
        FakeHolidaySyncService sync = new FakeHolidaySyncService(true);
        sync.stub(1, responseWithItems(item("20270101", "신정", "Y")));
        sync.failFor(2); // 2월 호출은 예외 발생
        sync.stub(3, responseWithItems(item("20270301", "삼일절", "Y")));

        MarketHolidayRepository repository = sync.repository;
        when(repository.findById(any())).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> sync.syncYear(2027), "한 달의 실패가 전체 배치를 중단시키면 안 됨");

        // 1월·3월은 정상 저장, 2월은 실패했지만 나머지에 영향 없음 — 총 2건 저장.
        verify(repository, times(2)).save(any());
        // 실패한 2월 때문에 이미 저장된 1월·3월 데이터를 지우는 호출(delete류)은 전혀 없어야 한다.
        verifyDeleteNeverCalled(repository);
    }

    @Test
    void enabled가_false면_API_호출도_저장도_하지_않는다() {
        FakeHolidaySyncService sync = new FakeHolidaySyncService(false);
        MarketHolidayRepository repository = sync.repository;

        sync.syncYear(2027);

        assertTrue(sync.calledYearMonths.isEmpty(), "enabled=false면 callApi가 한 번도 호출되지 않아야 함");
        verifyNoInteractions(repository);
    }

    @Test
    void 동기화_완료후_해당_연도_캐시를_무효화한다() {
        FakeHolidaySyncService sync = new FakeHolidaySyncService(true);
        when(sync.repository.findById(any())).thenReturn(Optional.empty());

        sync.syncYear(2027);

        verify(sync.marketCalendarService, times(1)).evictYear(2027);
    }

    @Test
    void syncNextYear은_현재_연도_다음해를_동기화한다() {
        FakeHolidaySyncService sync = new FakeHolidaySyncService(true);
        when(sync.repository.findById(any())).thenReturn(Optional.empty());

        sync.syncNextYear();

        int expectedYear = LocalDate.now(MarketConstants.KST).getYear() + 1;
        assertTrue(sync.calledYearMonths.stream().allMatch(k -> k.startsWith(expectedYear + "-")),
                "syncNextYear은 (현재 연도+1)년만 조회해야 함: " + sync.calledYearMonths);
        assertEquals(12, sync.calledYearMonths.size(), "1~12월 모두 조회해야 함");
    }

    // ── 테스트 헬퍼 ──────────────────────────────────────────────────────────────

    private static void verifyDeleteNeverCalled(MarketHolidayRepository repository) {
        verify(repository, never()).delete(any());
        verify(repository, never()).deleteAll();
        verify(repository, never()).deleteById(any());
    }

    private static Map<String, Object> responseWithItems(Map<String, Object>... items) {
        Map<String, Object> itemsWrapper = new HashMap<>();
        itemsWrapper.put("item", List.of(items));
        Map<String, Object> body = new HashMap<>();
        body.put("items", itemsWrapper);
        Map<String, Object> response = new HashMap<>();
        response.put("body", body);
        return Map.of("response", response);
    }

    private static Map<String, Object> item(String locdate, String dateName, String isHoliday) {
        Map<String, Object> m = new HashMap<>();
        m.put("locdate", locdate);
        m.put("dateName", dateName);
        m.put("isHoliday", isHoliday);
        m.put("dateKind", "01");
        return m;
    }

    /**
     * 실제 REST 호출({@link HolidaySyncService#callApi}) 없이 준비된 응답 Map으로 대체하는
     * 테스트 전용 서브클래스. WebClient.builder()는 실제 I/O 없이 빌더 체인만 구성하므로
     * 생성자에서 안전하게 호출할 수 있다(KiwoomDailyChartService를 스텁하는 C3LiveStrategyTest의
     * StubChartService와 같은 근거).
     */
    private static final class FakeHolidaySyncService extends HolidaySyncService {
        private final MarketHolidayRepository repository;
        private final MarketCalendarService marketCalendarService;
        private final Map<String, Map<String, Object>> stubbedResponses = new HashMap<>();
        private final Set<Integer> failMonths = new HashSet<>();
        private final List<String> calledYearMonths = new ArrayList<>();

        FakeHolidaySyncService(boolean enabled) {
            this(enabled, mock(MarketHolidayRepository.class), mock(MarketCalendarService.class));
        }

        private FakeHolidaySyncService(boolean enabled, MarketHolidayRepository repository,
                                        MarketCalendarService marketCalendarService) {
            super(WebClient.builder(),
                    new HolidayApiProperties(enabled, "test-key", "https://apis.data.go.kr/test"),
                    repository, marketCalendarService);
            this.repository = repository;
            this.marketCalendarService = marketCalendarService;
        }

        /** month만 지정 — 아무 연도로 syncYear를 호출해도 이 달이면 응답을 준다(연도 고정 안 하는 테스트용). */
        void stub(int month, Map<String, Object> response) {
            stubbedResponses.put("*-" + month, response);
        }

        void stub(int year, int month, Map<String, Object> response) {
            stubbedResponses.put(year + "-" + month, response);
        }

        void failFor(int month) {
            failMonths.add(month);
        }

        @Override
        protected Map<String, Object> callApi(int year, int month) {
            calledYearMonths.add(year + "-" + month);
            if (failMonths.contains(month)) {
                throw new RuntimeException("테스트 강제 실패(month=" + month + ")");
            }
            Map<String, Object> exact = stubbedResponses.get(year + "-" + month);
            if (exact != null) {
                return exact;
            }
            Map<String, Object> wildcard = stubbedResponses.get("*-" + month);
            if (wildcard != null) {
                return wildcard;
            }
            return responseWithItems(); // 특일 없는 달 — 빈 item 목록
        }
    }
}

package com.autostock.market;

import com.autostock.common.util.MarketConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 공공데이터포털 "특일 정보"(한국천문연구원, {@code SpcdeInfoService}) 연동 — 매년 11월,
 * 다음 해 공휴일을 {@code market_holidays} 테이블에 동기화하는 배치.
 *
 * <h2>왜 getRestDeInfo인가 — getAnniversaryInfo가 아니라</h2>
 * 이 서비스가 제공하는 오퍼레이션 중 사용자가 참고한 문서에는 {@code getAnniversaryInfo}
 * (기념일 정보)도 있다. 하지만 그 오퍼레이션은 {@code dateKind=02}(기념일)로 스승의날·
 * 식목일처럼 <b>거래소가 쉬지 않는 날</b>까지 대량으로 내려주고, {@code isHoliday}가 대부분
 * {@code N}이다 — "휴장일 판정"이라는 목적에는 맞지 않는다. 반대로 {@code getRestDeInfo}
 * (공휴일 정보)는 {@code dateKind=01}(국경일 등 법정 공휴일) 위주로, {@code isHoliday=Y}인
 * 날만 내려준다는 문서상 계약이 "실제로 쉬는 날"이라는 이 배치의 목적과 정확히 일치한다.
 * 그래서 이 클래스는 <b>항상 getRestDeInfo만</b> 호출하고, isHoliday=N인 항목은(문서상
 * 이 오퍼레이션에서는 거의 없어야 하지만) 방어적으로 한 번 더 걸러낸다({@link #syncYear}).
 *
 * <h2>왜 매년 11월인가</h2>
 * 사용자 운영 정책 — 다음 해 거래 캘린더를 미리 확정해 두고 싶어 한다. 공공데이터포털의
 * 특일 정보는 통상 그 해 초에 다음 해분까지 고시가 끝나 있으므로, 11월이면 다음 해
 * 데이터가 안정적으로 확정돼 있다고 보고 이 시점을 동기화 기준으로 잡았다
 * ({@link #syncNextYear} — {@code cron = "0 0 9 1 11 *"}, 매년 11/1 09:00 KST).
 *
 * <h2>부분 실패 정책</h2>
 * 1~12월을 월별로 호출한다(TR/월 파라미터 제약). 어느 한 달이 실패해도 이미 성공한
 * 달의 upsert 결과는 그대로 남고, 실패한 달은 error 로그만 남긴 채 다음 달로 넘어간다.
 * <b>부분 실패가 기존 DB의 데이터를 지우는 일은 절대 없다</b> — 이 배치는 "새로 받은 것만
 * 추가/갱신"하는 append/upsert 전용이고, 연도 전체를 삭제 후 재삽입하는 방식을 의도적으로
 * 피했다(실패한 재동기화가 이전 정상 데이터를 날려버리는 사고를 막기 위해).
 *
 * <h2>거래소 자체 휴장 보완 — MANUAL 등록</h2>
 * 특일 정보 API는 "법정 공휴일"만 내려주고, 거래소가 관행적으로 정하는 연말휴장(12/31)이나
 * 임시휴장(예: 대규모 시스템 점검일) 같은 날은 응답에 포함되지 않는다. 이런 날은 API로
 * 자동 동기화되지 않으므로 운영자가 직접 등록해야 한다. 등록 방법:
 * <pre>
 *   INSERT INTO market_holidays (holiday_date, name, source, is_market_closure, synced_at)
 *   VALUES ('2026-12-31', '연말휴장', 'MANUAL', true, now())
 *   ON CONFLICT (holiday_date) DO NOTHING;
 * </pre>
 * 별도 Flyway 마이그레이션(예: {@code V4__manual_holiday_2026.sql})으로 추가하거나, 운영
 * DB 클라이언트로 직접 실행한다. {@link MarketCalendarService}는 source 구분 없이 날짜
 * 존재 여부만으로 판정하므로, MANUAL로 등록해도 DATA_GO_KR과 동일하게 휴장일로 인식된다 —
 * 다만 등록 후에는 {@link MarketCalendarService#evictYear(int)}를 호출하거나 앱을 재시작해야
 * 캐시에 반영된다(운영 스크립트에서 DB INSERT 직후 evictYear 호출을 함께 넣는 것을 권장).
 *
 * <h2>공식 명세 확인 사항 (SC-OA-09-04, 2026-08-13 사용자 제공)</h2>
 * <ul>
 *   <li>{@code _type=json} 공식 지원 확인(교환 데이터 표준: XML+JSON) — JSON 파싱 유지.</li>
 *   <li>데이터 갱신: 연 1회 일괄. 특일(공휴일)은 6~8월 월력요항 발표 후 <b>+2년치</b>가
 *       먼저 올라온다 → 11월 연간 배치 시점엔 내년 데이터가 확실히 존재(타이밍 안전).</li>
 *   <li>임시공휴일은 발생 시 최대 1일 내 반영, 대체공휴일은 대통령령 시행 후 반영 —
 *       월 15일 재동기화({@link #resyncUpcomingWindow})가 이를 흡수한다. 임시공휴일을
 *       더 빨리 반영해야 하면 재동기화 주기를 주 단위로 좁히면 된다(트래픽 여유 충분).</li>
 *   <li>제헌절은 이 오퍼레이션에서 제공되지 않음(2008년부터 비공휴일이라 휴장 판정에 무관).</li>
 *   <li>페이지네이션: 월별 특일이 50건을 넘지 않으므로 numOfRows=50, pageNo=1 고정.</li>
 * </ul>
 */
@Service
public class HolidaySyncService {

    private static final Logger log = LoggerFactory.getLogger(HolidaySyncService.class);

    /** locdate(예: "20260101")를 LocalDate로 파싱하는 포맷. */
    private static final DateTimeFormatter LOCDATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String OPERATION = "getRestDeInfo";

    private final WebClient webClient;
    private final HolidayApiProperties properties;
    private final MarketHolidayRepository repository;
    private final MarketCalendarService marketCalendarService;

    public HolidaySyncService(WebClient.Builder webClientBuilder,
                              HolidayApiProperties properties,
                              MarketHolidayRepository repository,
                              MarketCalendarService marketCalendarService) {
        this.webClient = webClientBuilder.baseUrl(properties.baseUrl()).build();
        this.properties = properties;
        this.repository = repository;
        this.marketCalendarService = marketCalendarService;
    }

    /**
     * 매년 11월 1일 09:00 KST — 다음 해(내년) 특일 정보를 동기화한다.
     * 사용자 운영 정책("매년 11월에 내년 특일정보를 자체 DB에 저장") 그대로다.
     */
    @Scheduled(cron = "0 0 9 1 11 *", zone = "Asia/Seoul")
    public void syncNextYear() {
        int nextYear = LocalDate.now(MarketConstants.KST).getYear() + 1;
        syncYear(nextYear);
    }

    /**
     * 매월 15일 09:10 KST — <b>향후 30일 창(이번 달 + 다음 달)만 재동기화</b>한다.
     * (사용자 운영 정책: "매월 15일 단위로 30일 단위 재동기화")
     *
     * <p>왜 필요한가: 대체공휴일은 법제처 심사·국무회의·대통령 승인을 거쳐 관보에 정식
     * 공포된 <b>이후에야</b> API 응답에 반영된다(공공데이터포털 문서 명시). 즉 11월 연간
     * 동기화 시점에는 없던 휴일이 몇 달 뒤 새로 생길 수 있다 — 임박한 구간을 매월 다시
     * 받아 upsert하면 이런 늦은 확정을 놓치지 않는다. 창을 30일로 좁게 잡은 이유는
     * 트래픽 절약(월 2회 호출)과 "임박한 날짜일수록 정확해야 한다"는 우선순위 때문이다.
     */
    @Scheduled(cron = "0 10 9 15 * *", zone = "Asia/Seoul")
    public void resyncUpcomingWindow() {
        LocalDate today = LocalDate.now(MarketConstants.KST);
        LocalDate nextMonth = today.plusMonths(1);
        syncMonths(List.of(
                new YearMonthPair(today.getYear(), today.getMonthValue()),
                new YearMonthPair(nextMonth.getYear(), nextMonth.getMonthValue())));
    }

    /**
     * 지정한 (연,월) 목록만 동기화한다 — 연간 배치({@link #syncYear})와 같은 부분 실패
     * 정책(월 단위 격리, upsert 전용)을 그대로 쓴다.
     */
    public void syncMonths(List<YearMonthPair> months) {
        if (!properties.enabled()) {
            log.info("특일 API 연동 비활성 — 월 창 재동기화 스킵: {}", months);
            return;
        }
        int savedCount = 0;
        int failedMonths = 0;
        for (YearMonthPair ym : months) {
            try {
                for (RestDeItem item : fetchMonth(ym.year(), ym.month())) {
                    if (item.isHoliday()) {
                        upsert(item);
                        savedCount++;
                    }
                }
            } catch (RuntimeException e) {
                failedMonths++;
                log.error("특일 월 재동기화 실패({}) — 건너뛰고 기존 DB 유지", ym, e);
            }
        }
        log.info("특일 월 창 재동기화 완료: {}개월, 저장/갱신={}건, 실패={}개월", months.size(), savedCount, failedMonths);
        // 창이 연말을 걸치면 두 해의 캐시가 모두 영향을 받을 수 있다 — 관련 연도 전부 evict.
        months.stream().map(YearMonthPair::year).distinct().forEach(marketCalendarService::evictYear);
    }

    /** 재동기화 대상 (연, 월) 쌍 — 12월 창이 다음 해 1월로 넘어가는 경우를 표현하기 위해 연도를 함께 든다. */
    public record YearMonthPair(int year, int month) {
    }

    /**
     * 지정한 연도의 특일 정보를 1~12월 조회해 DB에 upsert한다. 스케줄 외에도 운영자가
     * 임의 연도를 즉시(재)동기화하고 싶을 때(예: 서비스키 신규 발급 직후 수동 백필, 과거
     * 배치 실패분 재시도) 직접 호출할 수 있도록 public으로 둔다.
     *
     * <p>{@code enabled=false}면 아무 것도 하지 않는다(서비스키 미발급 상태의 기본값,
     * {@link HolidayApiProperties} 참고) — 예외를 던지지 않고 조용히 스킵하는 이유는,
     * cron 스케줄이 비활성 상태에서도 매년 그대로 실행되기 때문이다(로그만 남긴다).
     */
    public void syncYear(int year) {
        if (!properties.enabled()) {
            log.info("특일 API 연동 비활성(market.holiday-api.enabled=false) — {}년 동기화 스킵", year);
            return;
        }

        int savedCount = 0;
        int failedMonths = 0;
        for (int month = 1; month <= 12; month++) {
            try {
                List<RestDeItem> items = fetchMonth(year, month);
                for (RestDeItem item : items) {
                    if (!item.isHoliday()) {
                        continue; // isHoliday=N은 휴장일이 아니므로 저장하지 않는다(클래스 설명 참고)
                    }
                    upsert(item);
                    savedCount++;
                }
            } catch (RuntimeException e) {
                // 부분 실패 정책(클래스 설명 참고) — 이 달만 건너뛰고 이미 저장된 다른 달 데이터는 그대로 둔다.
                failedMonths++;
                log.error("특일 API 호출/처리 실패(year={}, month={}) — 이 달은 건너뛰고 기존 DB는 그대로 유지", year, month, e);
            }
        }

        log.info("특일 동기화 완료: year={}, 저장/갱신={}건, 실패한 달={}개", year, savedCount, failedMonths);
        // 새로 저장된 데이터가 다음 조회부터 바로 반영되도록 캐시를 비운다. 0건만 성공했더라도
        // (예: 12개월 전부 실패) evictYear 자체는 안전하다 — 다음 조회 시 DB를 다시 읽을 뿐이다.
        marketCalendarService.evictYear(year);
    }

    private List<RestDeItem> fetchMonth(int year, int month) {
        Map<String, Object> response = callApi(year, month);
        return parseItems(response);
    }

    /**
     * 실제 HTTP 호출 지점 — protected로 열어 두어 테스트에서 WebClient를 직접 mocking하는
     * 대신 이 메서드를 오버라이드해 준비된 응답으로 대체할 수 있게 했다(이 저장소의 기존
     * 관례 — {@code strategy.C3LiveStrategyTest}의 {@code StubChartService}가
     * {@code KiwoomDailyChartService.fetchDaily}를 오버라이드하는 것과 같은 패턴).
     */
    protected Map<String, Object> callApi(int year, int month) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/" + OPERATION)
                        .queryParam("serviceKey", properties.serviceKey())
                        .queryParam("pageNo", 1)
                        .queryParam("numOfRows", 50)
                        .queryParam("solYear", year)
                        .queryParam("solMonth", String.format("%02d", month))
                        .queryParam("_type", "json") // TODO 실측: 미지원이면 XML 파싱으로 전환(클래스 설명 참고)
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .block();
    }

    /**
     * 공공데이터포털 표준 응답 포맷({@code response.body.items.item[]})을 파싱한다.
     * 예상과 다른 구조(TODO 실측 전이라 확정 아님)가 오면 예외를 던지지 않고 error 로그만
     * 남긴 뒤 빈 목록을 반환한다 — {@link #syncYear}의 부분 실패 정책과 같은 이유로,
     * 파싱 실패 하나가 이미 받은 다른 달의 성공 데이터를 지우면 안 되기 때문이다.
     */
    @SuppressWarnings("unchecked")
    private List<RestDeItem> parseItems(Map<String, Object> response) {
        if (response == null) {
            return List.of();
        }
        try {
            Map<String, Object> responseWrapper = (Map<String, Object>) response.get("response");
            Map<String, Object> body = (Map<String, Object>) responseWrapper.get("body");
            Map<String, Object> itemsWrapper = (Map<String, Object>) body.get("items");
            Object itemObj = (itemsWrapper == null) ? null : itemsWrapper.get("item");

            List<Map<String, Object>> rawItems;
            if (itemObj instanceof List<?> list) {
                rawItems = (List<Map<String, Object>>) list;
            } else if (itemObj instanceof Map<?, ?> single) {
                // 공공데이터포털 계열 API는 결과가 1건이면 배열이 아니라 단일 객체로 오는 경우가
                // 흔하다 — 방어적으로 단건도 리스트로 감싼다(TODO 실측 후 실제 동작 확인 필요).
                rawItems = List.of((Map<String, Object>) single);
            } else {
                // 해당 월에 특일이 아예 없으면 item 자체가 없을 수 있다 — 정상 케이스로 취급.
                return List.of();
            }

            List<RestDeItem> result = new ArrayList<>(rawItems.size());
            for (Map<String, Object> raw : rawItems) {
                result.add(toItem(raw));
            }
            return result;
        } catch (RuntimeException e) {
            log.error("특일 API 응답 파싱 실패 — 예상 포맷과 다름(TODO 실측 필요): {}", response, e);
            return List.of();
        }
    }

    private RestDeItem toItem(Map<String, Object> raw) {
        LocalDate date = LocalDate.parse(String.valueOf(raw.get("locdate")), LOCDATE_FORMAT);
        String dateName = String.valueOf(raw.get("dateName"));
        boolean isHoliday = "Y".equals(raw.get("isHoliday"));
        return new RestDeItem(date, dateName, isHoliday);
    }

    /**
     * 있으면 이름/동기화시각만 갱신, 없으면 새로 생성 — {@link MarketHolidayRepository#findById}는
     * 트랜잭션 프록시 메서드 호출이라 반환된 엔티티가 이미 detached 상태이므로, 필드를 바꾼
     * 뒤 반드시 {@code save}를 다시 호출해야 반영된다(더티체킹에 기대지 않는다).
     */
    private void upsert(RestDeItem item) {
        Instant now = Instant.now();
        MarketHolidayEntity entity = repository.findById(item.date())
                .map(existing -> {
                    existing.updateFromSync(item.dateName(), now);
                    return existing;
                })
                .orElseGet(() -> new MarketHolidayEntity(item.date(), item.dateName(), "DATA_GO_KR", false, now));
        repository.save(entity);
    }

    /** getRestDeInfo 응답 item 한 건을 파싱한 결과 — locdate/dateName/isHoliday만 이 배치에 필요하다(dateKind는 사용하지 않음). */
    private record RestDeItem(LocalDate date, String dateName, boolean isHoliday) {
    }
}

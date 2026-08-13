package com.autostock.marketdata;

import com.autostock.common.event.Candle;
import com.autostock.common.util.KiwoomNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 키움 일봉(ka10081, {@code /api/dostk/chart}) 조회 결과를 백테스트/전략이 바로 쓸 수 있는
 * {@link Candle} 목록으로 변환하는 수집 서비스.
 *
 * <p>실측(2026-08-13, mockapi.kiwoom.com) 응답 포맷:
 * <pre>
 *   {"stk_cd":"005930","stk_dt_pole_chart_qry":[
 *     {"cur_prc":"269000","trde_qty":"13065302","dt":"20260813",
 *      "open_pric":"267500","high_pric":"270000","low_pric":"262500","pred_pre":"+13500", ...},
 *     ...
 *   ]}
 * </pre>
 * 배열은 <b>최신일부터 내림차순</b>으로 온다 — 이 서비스는 백테스트가 기대하는 시간순(오름차순)으로
 * 뒤집어 반환한다.
 */
@Service
public class KiwoomDailyChartService {

    private static final Logger log = LoggerFactory.getLogger(KiwoomDailyChartService.class);

    private static final DateTimeFormatter BASE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String CHART_FIELD = "stk_dt_pole_chart_qry";

    private final MarketQueryService marketQueryService;

    public KiwoomDailyChartService(MarketQueryService marketQueryService) {
        this.marketQueryService = marketQueryService;
    }

    /**
     * @param symbol   종목코드 (예: "005930")
     * @param baseDate 조회 기준일 — 이 날짜를 기준으로 과거 방향 일봉을 받아온다
     * @param minCount 최소 필요 캔들 개수. 실제 수신 개수가 이보다 적으면 예외 없이 경고 로그만
     *                 남기고 받은 만큼만 반환한다(연속조회 미구현 — 아래 TODO 참고).
     * @return 날짜 오름차순(과거 → 최신)으로 정렬된 캔들 목록
     */
    @SuppressWarnings("unchecked")
    public List<Candle> fetchDaily(String symbol, LocalDate baseDate, int minCount) {
        Map<String, Object> response = marketQueryService.dailyChart(symbol, baseDate.format(BASE_DATE_FORMAT));

        // TODO: 연속조회(페이지네이션) 미구현. 키움 REST는 조회 결과가 많으면 응답 헤더의
        //  cont-yn(Y/N)과 next-key로 다음 페이지를 이어서 받아오는 방식을 지원한다.
        //  지금은 단건 호출로 받은 한 페이지 분량만 반환한다 — Phase 1 검증 범위에서는
        //  종목당 minCount(예: 20~수백봉) 정도면 한 페이지로 충분해 보류했다.
        Object rawList = response.get(CHART_FIELD);
        if (!(rawList instanceof List<?> rows) || rows.isEmpty()) {
            log.warn("일봉 응답에 {} 필드가 없거나 비어 있음 (symbol={}, baseDate={})", CHART_FIELD, symbol, baseDate);
            return List.of();
        }

        List<Candle> candles = new ArrayList<>(rows.size());
        for (Object row : rows) {
            candles.add(toCandle(symbol, (Map<String, Object>) row));
        }
        // 응답은 최신일 → 과거 순(내림차순)이므로, 백테스트가 기대하는 시간순(오름차순)으로 뒤집는다.
        Collections.reverse(candles);

        if (candles.size() < minCount) {
            log.warn("요청한 최소 개수({})보다 적은 캔들({})만 수신함 — 연속조회 미구현 (symbol={}, baseDate={})",
                    minCount, candles.size(), symbol, baseDate);
        }
        return candles;
    }

    /**
     * 키움 REST 응답의 가격류 필드는 등락 표시를 위해 {@code "+13500"}/{@code "-13500"}처럼
     * 부호가 붙어 올 수 있다(실측: ka10081의 {@code pred_pre}. {@code cur_prc}도 다른 TR에서는
     * 부호가 붙는 관례가 있어, 여기서도 안전하게 방어적으로 부호를 벗겨낸다). 캔들의 OHLC는
     * 항상 0 이상이어야 하므로 부호를 제거한 절대값으로 취급한다.
     * 부호 정규화는 {@link KiwoomNumbers}로 공통화했다(marketdata·WS 파서 중복 제거, PLAN ADR-5).
     */
    private Candle toCandle(String symbol, Map<String, Object> bar) {
        LocalDate date = LocalDate.parse(String.valueOf(bar.get("dt")), BASE_DATE_FORMAT);
        return new Candle(
                symbol,
                date,
                KiwoomNumbers.toBigDecimal(bar.get("open_pric")),
                KiwoomNumbers.toBigDecimal(bar.get("high_pric")),
                KiwoomNumbers.toBigDecimal(bar.get("low_pric")),
                KiwoomNumbers.toBigDecimal(bar.get("cur_prc")),
                KiwoomNumbers.toLong(bar.get("trde_qty")));
    }
}

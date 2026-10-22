package com.autostock.market;

import com.autostock.common.event.Candle;
import com.autostock.common.util.StockNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 일봉을 백테스트/전략이 바로 쓰는 날짜 오름차순 {@link Candle} 목록으로 조회한다.
 * 브로커 응답 번역은 {@link MarketDataPort} 구현체가 맡는다 — 이름의 "Kiwoom"은 A2 이전 이름을 유지한 것이다.
 */
@Service
public class KiwoomDailyChartService {

    private static final Logger log = LoggerFactory.getLogger(KiwoomDailyChartService.class);

    private final MarketDataPort marketData;

    public KiwoomDailyChartService(MarketDataPort marketData) {
        this.marketData = marketData;
    }

    /**
     * @param symbol   종목코드 (예: "005930")
     * @param baseDate 조회 기준일 — 이 날짜를 기준으로 과거 방향 일봉을 받아온다
     * @param minCount 최소 필요 캔들 개수. 실제 수신 개수가 이보다 적으면 예외 없이 경고 로그만
     *                 남기고 받은 만큼만 반환한다(연속조회 미구현 — 아래 TODO 참고).
     * @return 날짜 오름차순(과거 → 최신)으로 정렬된 캔들 목록
     */
    public List<Candle> fetchDaily(String symbol, LocalDate baseDate, int minCount) {
        // TODO: 연속조회(페이지네이션) 미구현. 키움 REST는 조회 결과가 많으면 응답 헤더의
        //  cont-yn(Y/N)과 next-key로 다음 페이지를 이어서 받아오는 방식을 지원한다.
        //  지금은 단건 호출로 받은 한 페이지 분량만 반환한다 — Phase 1 검증 범위에서는
        //  종목당 minCount(예: 20~수백봉) 정도면 한 페이지로 충분해 보류했다.
        List<Candle> candles = marketData.dailyCandles(symbol, baseDate);
        if (!candles.isEmpty() && candles.size() < minCount) {
            log.warn("요청한 최소 개수({})보다 적은 캔들({})만 수신함 — 연속조회 미구현 (종목={}, baseDate={})",
                    minCount, candles.size(), StockNames.label(symbol), baseDate);
        }
        return candles;
    }

    /**
     * 판단용 일봉 — <b>오늘(baseDate) 날짜 봉을 뺀</b> 완료된 봉만(2026-10-16).
     *
     * <p>실측(2026-10-16 09:40): ka10081은 장중에도 오늘 진행 중인 봉을 맨 앞에 준다(KODEX 200 {@code dt=20261016},
     * {@code cur_prc}=그 시각 가격). 그래서 09:05에 "마지막 봉 = 어제 종가"로 쓰면 실제로는 09:05 가격을 쓰게 된다 —
     * 백테스트(어제 종가)와 라이브가 갈라진다. 장전·장중 판단(C3·다전략)은 이 메서드를 쓴다. 장 마감 뒤 오늘 봉이
     * 필요한 곳(상장일 시세 15:40)은 {@link #fetchDaily}를 쓴다.
     *
     * @param today    판단일(KST) — 이 날짜와 그 이후 봉은 뺀다
     * @param minCount 최소 필요 개수(경고 기준)
     */
    public List<Candle> fetchCompletedDaily(String symbol, LocalDate today, int minCount) {
        List<Candle> all = marketData.dailyCandles(symbol, today);
        List<Candle> completed = all.stream().filter(c -> c.date() != null && c.date().isBefore(today)).toList();
        if (!completed.isEmpty() && completed.size() < minCount) {
            log.warn("요청한 최소 개수({})보다 적은 완료 봉({})만 수신함 (종목={}, 기준일={})",
                    minCount, completed.size(), StockNames.label(symbol), today);
        }
        return completed;
    }
}

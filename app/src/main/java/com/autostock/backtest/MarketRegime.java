package com.autostock.backtest;

import com.autostock.common.event.Candle;
import com.autostock.strategy.RegimeMath;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 시장 지수(예: KODEX200) 캔들로부터 날짜별 국면(ON/OFF)을 미리 계산해두는 유틸.
 *
 * <h2>왜 "사전 계산" 방식인가</h2>
 * 국면 판정은 개별 종목과 무관하게 시장 전체를 대표하는 지수 하나로 정해진다({@link
 * RealDataRegimeVolExperimentTest}에서는 5종목 슬리브 전부가 같은 KODEX200 국면 맵을
 * 공유한다). 그래서 매매 전략(종목별로 여러 번, walk-forward 창마다 새로 만들어지는)
 * 안에서 매번 SMA를 다시 계산하게 만들지 않고, 지수 캔들 전체를 한 번만 훑어 "날짜 →
 * ON/OFF" 맵을 만들어 여러 전략 인스턴스가 그 맵을 그대로 참조(read-only)하게 한다.
 *
 * <h2>판정 규칙 — 룩어헤드 방지</h2>
 * 오늘(map의 키가 되는 날짜) 판정에는 <b>전일까지</b>의 종가만 쓴다: 전일 종가와,
 * 전일까지의 200일 단순이동평균(SMA200)을 비교한다. 전일 종가가 SMA200을 초과하면
 * ON(상승 국면), 그렇지 않으면(이하) OFF(하락/횡보 국면)로 본다. <b>오늘 캔들의 시가/
 * 고가/저가/종가는 전혀 쓰지 않는다</b> — 판정에 필요한 데이터가 전부 "오늘이 시작되기
 * 전에 이미 확정된" 값이므로 룩어헤드가 아니다.
 *
 * <p>SMA200을 계산할 만큼 과거 데이터(200봉)가 아직 쌓이지 않은 초반 구간은 "국면을 판단할
 * 근거가 없다"는 뜻이므로 보수적으로 막지 않고 ON(중립 — 필터가 개입하지 않음)으로 처리한다.
 */
public final class MarketRegime {

    /** SMA 창 길이(봉 수) — 약 1년(거래일 기준) 추세를 보는 장기 이동평균. {@link RegimeMath#SMA_WINDOW}와 동일. */
    private static final int SMA_WINDOW = RegimeMath.SMA_WINDOW;

    private MarketRegime() {
    }

    /**
     * 지수 캔들 목록(시간순 정렬)으로부터 날짜별 ON/OFF 맵을 만든다.
     *
     * <p>실제 판정식(SMA200 계산·비교)은 {@link RegimeMath#isOn}으로 옮겼다(백테스트=라이브
     * 동형, PLAN 4절) — 이 메서드는 지수 캔들 전체를 훑으며 날짜마다 그 판정식을 호출해
     * 맵으로 미리 계산해두는 "사전 계산" 책임만 진다(클래스 설명 참고).
     *
     * @param indexCandles 시장 대표 지수(예: KODEX200)의 시간순 정렬된 캔들 전체
     * @return 날짜 → ON(true)/OFF(false). 입력 캔들과 1:1 대응(같은 날짜 개수).
     */
    public static Map<LocalDate, Boolean> compute(List<Candle> indexCandles) {
        Map<LocalDate, Boolean> regimeByDate = new LinkedHashMap<>();
        for (int i = 0; i < indexCandles.size(); i++) {
            LocalDate date = indexCandles.get(i).date();
            if (i < SMA_WINDOW) {
                // 아직 SMA200을 계산할 200봉이 쌓이지 않음 — RegimeMath.isOn도 같은 경우 ON을
                // 반환하지만, 여기서는 별도 슬라이스를 만들지 않기 위해 미리 걸러낸다(성능).
                regimeByDate.put(date, true);
                continue;
            }

            // "전일까지"의 종가만 사용한다 — closes[i-200 .. i-1] (오늘=i의 데이터는 전혀 참조하지 않음).
            // RegimeMath.isOn은 목록의 마지막 원소를 "전일 종가"로 취급하므로, 정확히 이 창을
            // 그대로 넘기면(마지막 원소=indexCandles[i-1].close()) 기존 계산과 동일한 결과가 나온다.
            List<BigDecimal> window = new ArrayList<>(SMA_WINDOW);
            for (int j = i - SMA_WINDOW; j < i; j++) {
                window.add(indexCandles.get(j).close());
            }
            boolean on = RegimeMath.isOn(window);
            regimeByDate.put(date, on);
        }
        return regimeByDate;
    }
}

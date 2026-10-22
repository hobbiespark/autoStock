package com.autostock.strategy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.LocalTime;
import java.util.List;

/**
 * 단타 변동성 돌파(VB-DAY) 설정 — 값은 사전 선언(aiDoc/multi-strategy-paper-2026-10-16.md 3-1)이다. 결과를 본 뒤 바꾸면 새 trial.
 *
 * @param enabled          기본 꺼짐 — 시작 스크립트가 켠다(C3와 같은 방식)
 * @param symbols          종목 — 2026-08-31 KRX 스냅샷 시가총액 상위 20 보통주
 * @param k                변동성 계수
 * @param smaDays          추세 필터 SMA 창(봉)
 * @param takeProfit       익절 폭(가격 기준, 0.018 = +1.8%)
 * @param stopLoss         손절 폭(가격 기준 양수, 0.015 = −1.5%)
 * @param entryUntil       이 시각 이후로는 새로 진입하지 않는다
 * @param exitAt           남은 보유를 파는 시각(시간 청산)
 * @param maxPositions     동시 보유 — 슬리브 설정(risk.multi-strategy)과 같게 둔다
 * @param maxEntriesPerDay 하루 진입 상한
 * @param buySlack         매수 지정가 여유(체결가 × (1 + buySlack))
 * @param sellSlack        매도 지정가 여유(기준가 × (1 − sellSlack))
 */
@ConfigurationProperties(prefix = "strategy.vb")
public record DayBreakoutProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue({"005930", "000660", "402340", "009150", "373220", "005380", "207940", "105560", "032830",
                "028260", "012450", "034020", "000270", "055550", "329180", "006400", "068270", "012330", "034730",
                "086790"}) List<String> symbols,
        @DefaultValue("0.5") double k,
        @DefaultValue("20") int smaDays,
        @DefaultValue("0.018") double takeProfit,
        @DefaultValue("0.015") double stopLoss,
        @DefaultValue("14:30") LocalTime entryUntil,
        @DefaultValue("15:15") LocalTime exitAt,
        @DefaultValue("3") int maxPositions,
        @DefaultValue("6") int maxEntriesPerDay,
        @DefaultValue("0.005") double buySlack,
        @DefaultValue("0.01") double sellSlack
) {
}

package com.autostock.risk;

import com.autostock.common.event.MarketDataStale;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 시세 단절 킬스위치 리스너 — market-data 모듈이 발행하는 {@link MarketDataStale}을 구독해
 * 킬스위치를 작동시킨다.
 *
 * <p>존재 이유: 시세 없는 채로 매매가 계속되는 사고를 막기 위해서다(PLAN 3절 — 커뮤니티
 * 사고 사례 1순위가 "WS가 끊긴 줄 모르고 있었다"). 이 리스너는 그 반대 실수를 막는다 —
 * "끊긴 걸 알고 있지만 그래도 계속 매매하는" 상황을 방지한다. risk 모듈은 market-data가
 * 어떻게 단절을 감지했는지 전혀 모르고, 이벤트 하나만으로 판단한다(모듈 경계 원칙).
 *
 * <p>재연결되어도 킬스위치는 자동으로 풀리지 않는다({@link KillSwitch} 설계상 해제는
 * 항상 사람(operator)의 명시적 행동이어야 한다) — 시세가 끊겼던 동안 놓친 상황이 있을 수
 * 있으므로, 사람이 상태를 확인하고 직접 재개하는 것이 안전하다는 판단이다.
 */
@Component
public class MarketDataStaleListener {

    private final KillSwitch killSwitch;

    public MarketDataStaleListener(KillSwitch killSwitch) {
        this.killSwitch = killSwitch;
    }

    @EventListener
    public void onMarketDataStale(MarketDataStale event) {
        killSwitch.engage("시세 단절 %d초(단절 시작: %s)".formatted(event.seconds(), event.disconnectedSince()));
    }
}

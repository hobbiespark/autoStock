package com.autostock.risk;

import com.autostock.common.event.OrderSequenceRestored;
import com.autostock.common.util.MarketConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 일간 한도 추적기 — "오늘 주문을 몇 번 냈나"를 세는 카운터이자 주문 일련번호 발급기.
 *
 * <p>존재 이유: 전략 버그로 시그널이 폭주하면 주문이 수백 건 나갈 수 있다.
 * RiskGate의 다른 검사를 다 통과하더라도 하루 총량에는 상한이 있어야 한다.
 * 마지막 방어선. (일 손실 한도는 잔고 연동 후 Phase 2 후반 추가)
 *
 * <p>날짜 롤오버 주의점: "오늘"의 기준은 KST(한국시간)다. 서버가 UTC로 돌아도
 * 자정(KST)에 카운터가 리셋되도록 {@link MarketConstants#KST}를 명시했다
 * (여러 모듈이 각자 ZoneId를 선언하던 것을 공용 상수로 통합, PLAN ADR-5).
 *
 * <p><b>원자성(실행 계획 1.2, BE-P1-3·P2-17, 2026-10-02 — aiDoc/open-order-aware-risk.md)</b>: 날짜와 개수를
 * 불변 값 하나({@link DayCount})로 묶어 {@link AtomicReference} 하나에 담는다. 슬롯 획득은 "날짜 확인 → 한도 확인 →
 * 증가"를 compareAndSet 한 번으로 끝내고, 얻은 일련번호를 그 날짜와 함께 돌려준다({@link OrderSlot}).
 * 예전에는 ① 롤오버가 "날짜 CAS 뒤 개수 0으로 set" 두 단계라 자정 직후 다른 스레드의 증가가 지워질 수 있었고
 * ② RiskGate가 증가한 뒤 {@link #todayOrderCount()}를 다시 읽어 일련번호로 써서, 두 스레드가 동시에 통과하면
 * 같은 번호를 받을 수 있었다(ClientOrderId UNIQUE 충돌 → 주문 유실).
 * <ul>
 *   <li>한도를 넘은 시도는 개수를 올리지 않는다 — 개수는 "오늘 실제로 쓴 슬롯 수"다.</li>
 *   <li>시계가 뒤로 가도(시간 보정 등) 날짜를 되돌리지 않는다 — 같은 날 번호가 1부터 다시 나와 겹치는 일을 막는다.</li>
 * </ul>
 */
@Component
public class DailyLimitTracker {

    private static final Logger log = LoggerFactory.getLogger(DailyLimitTracker.class);

    private final RiskProperties properties;
    private final Clock clock;
    private final AtomicReference<DayCount> state;

    public DailyLimitTracker(RiskProperties properties, Clock clock) {
        this.clock = clock;
        this.properties = properties;
        this.state = new AtomicReference<>(new DayCount(today(), 0));
    }

    /**
     * 주문 슬롯 획득 시도.
     *
     * @return 한도 안이면 이번 주문의 날짜(KST)와 그날의 일련번호(1부터), 한도에 닿았으면 빈 값
     */
    public Optional<OrderSlot> tryAcquireOrderSlot() {
        LocalDate today = today();
        int max = properties.dailyMaxOrders();
        while (true) {
            DayCount current = state.get();
            DayCount base = current.rolledTo(today);
            if (base.count() >= max) {
                return Optional.empty();
            }
            DayCount next = new DayCount(base.day(), base.count() + 1);
            if (state.compareAndSet(current, next)) {
                return Optional.of(new OrderSlot(next.day(), next.count()));
            }
            // 다른 스레드가 먼저 바꿨다 — 새 값으로 다시 계산한다
        }
    }

    /** 오늘(KST) 쓴 슬롯 수. 읽기만 한다(날짜가 바뀌었으면 0). */
    public int todayOrderCount() {
        return state.get().rolledTo(today()).count();
    }

    /**
     * 재시작 일련번호 복원 (운영 1일차 ① — trading.OrderSequenceRestorer가 기동 시 발행).
     * 카운터를 "당일 DB에 이미 존재하는 최대 일련번호" 이상으로 끌어올려, 재기동 후 첫
     * 주문이 기존 ClientOrderId와 UNIQUE 충돌하는 결함(2026-09-11 실측, -001~-003 3회
     * 스킵)을 막는다. 최댓값 방식이라 이벤트가 중복 수신돼도 안전하다(멱등).
     */
    @EventListener
    public void onOrderSequenceRestored(OrderSequenceRestored event) {
        LocalDate today = today();
        if (!event.day().equals(today) || event.lastSequence() <= 0) {
            return; // 날짜가 어긋난 복원(자정 직전 기동 등)은 무시 — 새 날은 0부터가 맞다
        }
        DayCount restored = state.updateAndGet(current -> {
            DayCount base = current.rolledTo(today);
            return new DayCount(base.day(), Math.max(base.count(), event.lastSequence()));
        });
        log.info("일 주문 카운터 복원: {} (일련번호·일 한도 겸용)", restored.count());
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(MarketConstants.KST));
    }

    /**
     * 얻은 주문 슬롯 — ClientOrderId의 날짜와 일련번호. 둘을 함께 받아야 자정 경계에서 "어제 번호 + 오늘 날짜"가
     * 섞이지 않는다.
     */
    public record OrderSlot(LocalDate day, int serial) {
    }

    /** 날짜와 그날 쓴 슬롯 수 — 한 덩어리로 바꿔야 롤오버와 증가가 섞이지 않는다. */
    private record DayCount(LocalDate day, int count) {

        /** today가 이 날짜보다 뒤면 새 날(0개), 아니면 그대로 — 시계가 뒤로 가도 되돌리지 않는다. */
        DayCount rolledTo(LocalDate today) {
            return today.isAfter(day) ? new DayCount(today, 0) : this;
        }
    }
}

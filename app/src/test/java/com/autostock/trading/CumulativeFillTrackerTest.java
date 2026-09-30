package com.autostock.trading;

import com.autostock.common.util.BrokerOrderId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 누적 평균가(FID 910) → 증분 단가 역산의 경계 — 예전에는 핸들러 전체(엔티티 스텁·통보)를 거쳐야 검증할 수 있었다(B3 B안).
 */
class CumulativeFillTrackerTest {

    private static final BrokerOrderId ORDER = new BrokerOrderId("0119433");
    private final CumulativeFillTracker tracker = new CumulativeFillTracker();

    private static BigDecimal won(String value) {
        return new BigDecimal(value);
    }

    @Test
    void 첫_체결은_평균가가_곧_증분_단가다() {
        var increment = tracker.increment(ORDER, won("70000"), 10, 0);

        assertEquals(won("70000"), increment.deltaPrice());
        assertEquals(0, won("700000").compareTo(increment.cumulativeNotional()));
    }

    @Test
    void 분할_체결_3회는_누적금액_차이로_각_증분_단가를_되돌린다() {
        // 7주 @70000, 이어서 누적 12주 평균 70100(→ 5주 @70240), 누적 19주 평균 70150(→ 7주 @70235.7143)
        var first = tracker.increment(ORDER, won("70000"), 7, 0);
        tracker.record(ORDER, first.cumulativeNotional(), false);

        var second = tracker.increment(ORDER, won("70100"), 12, 7);
        assertEquals(won("70240.0000"), second.deltaPrice());
        tracker.record(ORDER, second.cumulativeNotional(), false);

        var third = tracker.increment(ORDER, won("70150"), 19, 12);
        assertEquals(won("70235.7143"), third.deltaPrice());
    }

    @Test
    void 직전_누적금액을_모르면_이번_평균가로_근사한다() {
        // 재시작으로 기억이 없다 — (평균가×누적 − 평균가×직전) / 증분 = 평균가
        var increment = tracker.increment(ORDER, won("70100"), 12, 7);

        assertEquals(won("70100.0000"), increment.deltaPrice());
    }

    @Test
    void 평균가가_없으면_단가도_누적금액도_없다() {
        var increment = tracker.increment(ORDER, null, 12, 7);

        assertNull(increment.deltaPrice());
        assertNull(increment.cumulativeNotional());
    }

    @Test
    void 계산만으로는_기억이_바뀌지_않는다() {
        // 낙관적 잠금 충돌로 같은 통보를 두 번 계산해도 결과가 같아야 한다(R2)
        tracker.record(ORDER, won("490000"), false);   // 7주 @70000

        var once = tracker.increment(ORDER, won("70100"), 12, 7);
        var again = tracker.increment(ORDER, won("70100"), 12, 7);

        assertEquals(once, again);
    }

    @Test
    void 주문이_끝나면_잊는다() {
        tracker.record(ORDER, won("490000"), false);
        tracker.record(ORDER, won("841200"), true);

        // 잊었으므로 다음 계산은 근사로 돌아간다
        assertEquals(won("70100.0000"), tracker.increment(ORDER, won("70100"), 12, 7).deltaPrice());
    }
}

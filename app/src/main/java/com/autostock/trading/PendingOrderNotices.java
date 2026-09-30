package com.autostock.trading;

import com.autostock.common.event.OrderNotice;
import com.autostock.common.util.BrokerOrderId;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 브로커 주문번호가 등록되기 전에 도착한 체결 통보의 보류함(R4) — {@link OrderNoticeHandler}가 소유한다.
 * 주문 응답(REST)보다 WS 체결 통보가 먼저 오면 매핑이 없어 버려지는데, 즉시 전량 체결된 주문은 뒤따르는
 * 통보가 없어 체결이 영구 누락된다. 등록되면 꺼내 다시 처리하고, {@link #HOLD}가 지나면 버린다.
 */
final class PendingOrderNotices {

    /** 주문 응답이 WS보다 늦어도 이 안에는 온다(키움 REST 상한 15초 + 여유). */
    static final Duration HOLD = Duration.ofSeconds(30);
    /** 보류 건수 상한 — 수동 주문 체결이 몰려도 메모리가 무한히 늘지 않게 한다. */
    static final int MAX_PENDING = 200;

    private final Map<BrokerOrderId, List<Pending>> byBrokerOrderId = new ConcurrentHashMap<>();

    /** 통보를 보류한다. 상한을 넘으면 보류하지 않고 false. */
    boolean park(OrderNotice notice, Instant now) {
        if (size() >= MAX_PENDING) {
            return false;
        }
        byBrokerOrderId.compute(new BrokerOrderId(notice.brokerOrderId()), (id, list) -> {
            List<Pending> next = list == null ? new ArrayList<>() : new ArrayList<>(list);
            next.add(new Pending(notice, now));
            return next;
        });
        return true;
    }

    Set<BrokerOrderId> brokerOrderIds() {
        return Set.copyOf(byBrokerOrderId.keySet());
    }

    /** 해당 주문번호로 보류된 통보를 도착 순서대로 꺼낸다(없으면 빈 목록). 한 번 꺼낸 통보는 다시 나오지 않는다. */
    List<OrderNotice> drain(BrokerOrderId brokerOrderId) {
        List<Pending> removed = byBrokerOrderId.remove(brokerOrderId);
        return removed == null ? List.of() : removed.stream().map(Pending::notice).toList();
    }

    /** 보류 시간이 지난 통보를 꺼내 버린다 — 호출부가 경고를 남긴다. */
    List<OrderNotice> removeExpired(Instant now) {
        Instant cutoff = now.minus(HOLD);
        List<OrderNotice> expired = new ArrayList<>();
        for (BrokerOrderId brokerOrderId : brokerOrderIds()) {
            byBrokerOrderId.computeIfPresent(brokerOrderId, (id, list) -> {
                List<Pending> kept = new ArrayList<>();
                for (Pending p : list) {
                    if (p.parkedAt().isAfter(cutoff)) {
                        kept.add(p);
                    } else {
                        expired.add(p.notice());
                    }
                }
                return kept.isEmpty() ? null : kept;
            });
        }
        return expired;
    }

    int size() {
        return byBrokerOrderId.values().stream().mapToInt(List::size).sum();
    }

    private record Pending(OrderNotice notice, Instant parkedAt) {
    }
}

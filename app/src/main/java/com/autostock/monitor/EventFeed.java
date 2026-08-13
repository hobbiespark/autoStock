package com.autostock.monitor;

import com.autostock.common.event.Fill;
import com.autostock.common.event.MarketTick;
import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Signal;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 대시보드용 최근 이벤트 피드 — 메모리 링버퍼.
 *
 * <p>왜 DB(event_store)를 직접 조회하지 않나?
 * 대시보드는 "방금 무슨 일이 있었나"만 보여주면 되고, DB가 죽어 있어도
 * 화면은 떠야 한다. 그래서 최근 N건만 메모리에 유지한다.
 * 전체 이력 조회가 필요해지면 그때 audit 모듈 조회 API를 추가한다.
 *
 * <p>MarketTick은 초당 수십 건이라 피드를 덮어버리므로 의도적으로 제외했다.
 */
@Component
public class EventFeed {

    /** 화면에 보여줄 최대 이벤트 수 — 오래된 것부터 밀려난다. */
    private static final int CAPACITY = 100;

    /** 피드 항목: 종류 + 요약 문장 + 발생 시각. FE는 이 요약만 그대로 뿌린다. */
    public record FeedItem(String type, String summary, Instant at) {
    }

    private final Deque<FeedItem> items = new ArrayDeque<>();

    @EventListener
    public synchronized void on(Signal e) {
        add(new FeedItem("SIGNAL",
                "[%s] %s %s @ %s (전략 %s)".formatted(e.symbol(), e.side(), "시그널", e.refPrice(), e.strategyId()),
                e.timestamp()));
    }

    @EventListener
    public synchronized void on(OrderRequest e) {
        add(new FeedItem("ORDER",
                "[%s] %s %d주 @ %s 주문요청".formatted(e.symbol(), e.side(), e.quantity(), e.limitPrice()),
                e.timestamp()));
    }

    @EventListener
    public synchronized void on(Fill e) {
        add(new FeedItem("FILL",
                "[%s] %s %d주 @ %s 체결 (%s)".formatted(e.symbol(), e.side(), e.filledQuantity(), e.fillPrice(), e.brokerOrderId()),
                e.timestamp()));
    }

    private void add(FeedItem item) {
        items.addFirst(item);              // 최신이 맨 앞
        if (items.size() > CAPACITY) {
            items.removeLast();            // 넘치면 가장 오래된 것 제거
        }
    }

    /** 최신순 스냅샷 복사본 반환 — 원본을 밖에 노출하지 않는다. */
    public synchronized List<FeedItem> recent() {
        return new ArrayList<>(items);
    }
}

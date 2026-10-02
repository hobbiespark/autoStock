package com.autostock.monitor;

import com.autostock.common.event.DisclosureBlacklisted;
import com.autostock.portfolio.PositionBook;
import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 종료 순서(2026-10-02 실측 결함 — aiDoc/small-fixes-2026-10-02.md): 실제 Spring 컨텍스트를 닫을 때 남은 공시 요약과
 * 알림 대기열이 Reactor Netty 자원(ReactorResourceFactory, SmartLifecycle 단계 0)이 닫히기 <b>전에</b> 나가는지 본다.
 * 예전 {@code @PreDestroy}는 그 자원이 닫힌 뒤라 텔레그램 발송이 "event executor terminated"로 실패했다.
 */
class ShutdownOrderTest {

    @Test
    void 컨텍스트를_닫으면_남은_요약과_대기열을_Reactor_자원보다_먼저_보낸다() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        AtomicBoolean reactorClosed = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        Notifier notifier = (level, message) -> {
            if (message.startsWith("체결")) {
                try {
                    release.await(5, TimeUnit.SECONDS);   // 종료 때까지 대기열에 남아 있게 붙잡는다
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            order.add((reactorClosed.get() ? "늦음 — " : "") + message.lines().findFirst().orElse(""));
        };

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("reactorResourceFactoryLike", SmartLifecycle.class, () -> new ReactorLike(reactorClosed, order));
            context.registerBean(NotificationDispatcher.class, () -> new NotificationDispatcher(notifier));
            context.registerBean(DisclosureBlacklistListener.class,
                    () -> new DisclosureBlacklistListener(notifier, new PositionBook(), List.of()));
            context.refresh();

            context.getBean(NotificationDispatcher.class).submit(NoticeLevel.INFO, "체결 1");
            context.getBean(NotificationDispatcher.class).submit(NoticeLevel.INFO, "체결 2");
            context.getBean(DisclosureBlacklistListener.class).onDisclosureBlacklisted(new DisclosureBlacklisted(
                    "123010", "MSDI", "PAID_IN_CAPITAL_INCREASE", "20261002000389", LocalDate.of(2027, 3, 31),
                    Instant.parse("2026-10-02T09:55:47Z")));
            release.countDown();
        }   // close — 높은 단계부터: 요약(2048) → 대기열(1024) → Reactor 자원(0)

        assertTrue(order.stream().noneMatch(line -> line.startsWith("늦음")), order.toString());
        assertEquals("Reactor 자원 닫힘", order.get(order.size() - 1), order.toString());
        assertTrue(order.stream().anyMatch(line -> line.startsWith("공시 블랙리스트 신규 1건")), order.toString());
        assertTrue(order.containsAll(List.of("체결 1", "체결 2")), order.toString());
    }

    /** ReactorResourceFactory처럼 단계 0에서 멈추는 수명주기 빈 — 멈춘 뒤의 발송은 "늦음"으로 표시된다. */
    private static final class ReactorLike implements SmartLifecycle {
        private final AtomicBoolean closed;
        private final List<String> order;
        private volatile boolean running;

        ReactorLike(AtomicBoolean closed, List<String> order) {
            this.closed = closed;
            this.order = order;
        }

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop() {
            closed.set(true);
            order.add("Reactor 자원 닫힘");
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public int getPhase() {
            return 0;
        }
    }
}

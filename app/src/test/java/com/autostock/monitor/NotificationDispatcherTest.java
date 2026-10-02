package com.autostock.monitor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 알림 비동기 발송기(실행 계획 1.3) — 느린 알림이 발행 스레드를 막지 않는지, 순서·상한·종료 비우기를 확인한다.
 */
class NotificationDispatcherTest {

    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final List<String> threads = new CopyOnWriteArrayList<>();

    /** 첫 알림을 latch가 열릴 때까지 붙잡는 느린 알림 — 텔레그램 왕복·429 대기를 흉내 낸다. */
    private Notifier slowNotifier(CountDownLatch release) {
        return (level, message) -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            threads.add(Thread.currentThread().getName());
            sent.add(message);
        };
    }

    @Test
    void 느린_알림도_발행_스레드를_막지_않고_넣은_순서대로_보낸다() {
        CountDownLatch release = new CountDownLatch(1);
        NotificationDispatcher dispatcher = new NotificationDispatcher(slowNotifier(release));

        long started = System.nanoTime();
        dispatcher.submit(NoticeLevel.INFO, "체결 1");
        dispatcher.submit(NoticeLevel.CRITICAL, "킬스위치 작동");
        dispatcher.submit(NoticeLevel.WARN, "킬스위치 해제");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMs < 1_000, "발행 스레드는 기다리지 않는다: " + elapsedMs + "ms");
        assertTrue(sent.isEmpty(), "아직 첫 알림이 붙잡혀 있다");
        release.countDown();
        dispatcher.drain();
        assertEquals(List.of("체결 1", "킬스위치 작동", "킬스위치 해제"), sent);
        assertTrue(threads.stream().allMatch(name -> name.startsWith("notify-")), threads.toString());
    }

    @Test
    void 대기열이_넘치면_버리고_예외를_던지지_않는다() {
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolExecutor small = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(2));
        NotificationDispatcher dispatcher = new NotificationDispatcher(slowNotifier(release), small);

        for (int i = 1; i <= 5; i++) {
            int n = i;
            assertDoesNotThrow(() -> dispatcher.submit(NoticeLevel.INFO, "알림 " + n));
        }
        release.countDown();
        dispatcher.drain();

        assertEquals(3, sent.size(), "작업 중 1건 + 대기열 2건만 나가고 나머지는 버린다: " + sent);
        assertEquals("알림 1", sent.get(0));
    }

    @Test
    void 종료하면_남은_알림을_보내고_그_뒤_알림은_버린다() {
        NotificationDispatcher dispatcher = new NotificationDispatcher((level, message) -> {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sent.add(message);
        });
        for (int i = 1; i <= 5; i++) {
            dispatcher.submit(NoticeLevel.INFO, "알림 " + i);
        }

        dispatcher.drain();
        assertEquals(5, sent.size());

        assertDoesNotThrow(() -> dispatcher.submit(NoticeLevel.INFO, "종료 뒤"));
        assertEquals(5, sent.size());
    }

    @Test
    void 알림_하나가_예외를_던져도_다음_알림은_나간다() {
        NotificationDispatcher dispatcher = new NotificationDispatcher((level, message) -> {
            if (message.equals("터짐")) {
                throw new IllegalStateException("알림 구현 오류(테스트)");
            }
            sent.add(message);
        });

        dispatcher.submit(NoticeLevel.INFO, "터짐");
        dispatcher.submit(NoticeLevel.INFO, "다음");
        dispatcher.drain();

        assertEquals(List.of("다음"), sent);
    }
}

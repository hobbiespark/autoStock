package com.autostock.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 알림 비동기 발송기 (실행 계획 1.3, BE-P1-5, 2026-10-02 — aiDoc/async-notification.md).
 *
 * <p>{@link TradeNotificationListener}가 텔레그램 왕복(수백 ms, 발송 제한 429면 최대 10초 대기)을 발행 스레드에서 하지
 * 않게 한다. 체결은 WS 수신 스레드에서 발행되므로 그 스레드가 막히면 PING 에코가 늦어 연결이 끊길 수 있고, 주문 요청은
 * 리스너 실행 순서에 따라 브로커 전송이 알림 뒤로 밀릴 수 있었다.
 * <ul>
 *   <li><b>작업자 1개(가상 스레드)</b> — 넣은 순서대로 하나씩 보낸다. 여러 건을 동시에 쏘면 텔레그램 발송 제한에 더 자주
 *       걸리고, 킬스위치 작동·해제 같은 알림의 순서가 뒤집힐 수 있다.</li>
 *   <li><b>대기열 상한 {@value #QUEUE_CAPACITY}건</b> — 넘치면 버리고 ERROR 로그를 남긴다. 발행 스레드를 막지 않는 것이 우선이다.</li>
 *   <li><b>종료 시 비우기</b> — 남은 알림을 최대 {@link #DRAIN_TIMEOUT}까지 보내고 닫는다. {@code @PreDestroy}가 아니라
 *       {@link SmartLifecycle} 종료 단계 {@value #SHUTDOWN_PHASE}에서 한다 — {@code @PreDestroy} 시점엔 Reactor Netty 이벤트
 *       루프(단계 0)가 이미 닫혀 텔레그램 발송이 실패한다(2026-10-02 18:56 공시 요약 유실 실측). 스케줄러·웹 서버가 먼저
 *       멈추므로(더 높은 단계) 비운 뒤 새로 들어오는 알림은 거의 없다.</li>
 * </ul>
 *
 * <p>계획은 기본 {@code @Async}(요청마다 가상 스레드)였다. 위 순서·발송 간격 때문에 전용 작업자를 두었고, 이를 Spring
 * {@code Executor} 빈으로 등록하지 않은 것은 Spring Boot가 사용자 Executor 빈이 있으면 기본 실행기를 만들지 않아(3.5
 * {@code spring.task.execution.mode=auto}) 감사 기록({@code audit.EventAuditListener}의 {@code @Async})까지 이 작업자로
 * 몰리기 때문이다.
 */
@Component
public class NotificationDispatcher implements SmartLifecycle {

    /** 종료 단계 — 스케줄러·웹 서버보다 늦게, Reactor Netty 자원(ReactorResourceFactory, 0)보다 먼저. */
    static final int SHUTDOWN_PHASE = 1024;

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    static final int QUEUE_CAPACITY = 500;
    static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(10);

    private final Notifier notifier;
    private final ExecutorService executor;
    private volatile boolean running;

    @Autowired
    public NotificationDispatcher(Notifier notifier) {
        this(notifier, new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY), Thread.ofVirtual().name("notify-", 0).factory()));
    }

    /** 테스트용 — 대기열 크기·작업자를 바꿔 끼운다. */
    NotificationDispatcher(Notifier notifier, ExecutorService executor) {
        this.notifier = notifier;
        this.executor = executor;
    }

    /** 알림을 대기열에 넣고 바로 돌아온다. 넘치거나 종료 중이면 버린다(예외를 던지지 않는다). */
    public void submit(NoticeLevel level, String message) {
        try {
            executor.execute(() -> send(level, message));
        } catch (RejectedExecutionException e) {
            log.error("알림 대기열이 가득 찼거나 종료 중 — 버림: level={} message={}", level, message);
        }
    }

    private void send(NoticeLevel level, String message) {
        try {
            notifier.notify(level, message);
        } catch (RuntimeException e) {
            // 구현체가 이미 삼키지만(TelegramNotifier) 혹시 새어 나와도 다음 알림은 보낸다
            log.error("알림 발송 중 예외 — level={} message={}", level, message, e);
        }
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        try {
            drain();
        } finally {
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return SHUTDOWN_PHASE;
    }

    /** 종료 — 남은 알림을 {@link #DRAIN_TIMEOUT}까지 보내고 닫는다. 그 뒤 들어온 알림은 버린다. */
    void drain() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                int left = executor.shutdownNow().size();
                log.warn("종료 — 알림 {}건을 보내지 못하고 닫음({}초 초과)", left, DRAIN_TIMEOUT.toSeconds());
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

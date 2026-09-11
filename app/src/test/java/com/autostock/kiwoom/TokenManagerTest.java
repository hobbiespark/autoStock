package com.autostock.kiwoom;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TokenManager의 single-flight 발급 검증 — 실제 WebClient 호출 없이
 * {@link TokenManager#callApi()}를 오버라이드해 준비된 응답으로 대체한다
 * ({@code macrointel.FredClientTest}와 같은 패턴).
 *
 * <p>실측(2026-09-11 운영 로그) 재현: 캐시가 비어있는 상태에서 여러 스레드가 거의 동시에
 * {@link TokenManager#accessToken()}을 부르면, 락이 없으면 각자 issue()를 불러 실제 발급이
 * 스레드 수만큼 중복 발생한다(두 번째 이후 요청이 429). single-flight 수정 후에는
 * 실제 호출 횟수가 정확히 1이어야 한다.
 */
class TokenManagerTest {

    private static final KiwoomProperties PROPERTIES =
            new KiwoomProperties("https://mock.kiwoom.test", "wss://mock.kiwoom.test/ws", "test-key", "test-secret");

    @Test
    void 동시에_여러_스레드가_토큰을_요구해도_실제_발급은_한번만_일어난다() throws Exception {
        int threadCount = 20;
        FakeTokenManager tokenManager = new FakeTokenManager();
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        try {
            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        tokenManager.accessToken();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, tokenManager.callCount.get(), "동시 호출이어도 실제 발급(callApi)은 한 번만 일어나야 한다");
    }

    @Test
    void 캐시가_유효하면_추가_발급_없이_같은_토큰을_반환한다() {
        FakeTokenManager tokenManager = new FakeTokenManager();

        String first = tokenManager.accessToken();
        String second = tokenManager.accessToken();

        assertEquals(first, second);
        assertEquals(1, tokenManager.callCount.get());
    }

    /** 실제 HTTP 호출 없이 준비된 응답으로 대체하고, 호출 시마다 짧게 지연시켜 동시성 경합을 재현한다. */
    private static final class FakeTokenManager extends TokenManager {
        final AtomicInteger callCount = new AtomicInteger();

        FakeTokenManager() {
            super(WebClient.builder(), PROPERTIES);
        }

        @Override
        protected Map<String, Object> callApi() {
            callCount.incrementAndGet();
            try {
                // 경합 창을 넓혀 락이 없을 때 실제로 중복 호출이 나는지 드러낸다.
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Map.of(
                    "token", "test-token",
                    "return_code", 0,
                    "expires_dt", "20991231235959");
        }
    }
}

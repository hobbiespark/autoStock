package com.autostock.trading;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 주문 갱신의 낙관적 잠금 충돌 재시도 — 충돌하면 최신 주문을 다시 읽어 같은 변경을 다시 적용한다.
 * 변경 함수는 매 시도마다 처음부터 다시 실행되므로, 저장 전에 되돌릴 수 없는 부수효과(이벤트 발행,
 * 브로커 호출, 인메모리 누적치 갱신)를 넣지 않는다.
 */
final class OptimisticRetry {

    private static final Logger log = LoggerFactory.getLogger(OptimisticRetry.class);

    static final int MAX_ATTEMPTS = 3;

    private OptimisticRetry() {
    }

    /**
     * @param initial 첫 시도에 쓸 주문(null이면 첫 시도부터 {@code reload}로 읽는다)
     * @param reload  충돌 뒤 최신 주문을 읽는 방법 — 비어 있으면 null을 반환하고 끝낸다
     * @param update  변경·저장 후 결과를 돌려준다(null 가능)
     * @throws OptimisticLockingFailureException {@link #MAX_ATTEMPTS}회 모두 충돌한 경우
     */
    static <T> T run(String what, OrderEntity initial, Supplier<Optional<OrderEntity>> reload,
                     Function<OrderEntity, T> update) {
        OrderEntity current = initial;
        for (int attempt = 1; ; attempt++) {
            if (current == null) {
                current = reload.get().orElse(null);
                if (current == null) {
                    return null;
                }
            }
            try {
                return update.apply(current);
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.info("주문 동시 갱신 충돌 — 최신 상태로 재시도({}/{}): {}", attempt, MAX_ATTEMPTS, what);
                current = null;
            }
        }
    }
}

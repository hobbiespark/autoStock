package com.autostock.monitor;

import com.autostock.common.util.StockNames;
import com.autostock.portfolio.PositionBook;
import com.autostock.risk.KillSwitch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 텔레그램 주식 제어 명령의 의미 — 기존 {@link TelegramCommandPoller}와 플랫폼 주식 제어 입구(P2-2d, VALUNIVO 159 §5)가 같은
 * 인스턴스를 공유한다. 의미를 두 군데 따로 두지 않기 위해서다(159 §8-1). /resume 확인 코드도 하나뿐이다.
 *
 * <ul>
 *   <li>stop — killSwitch.engage("텔레그램 원격 명령") 즉시</li>
 *   <li>resume 요청 — 킬스위치 작동 중일 때만 무작위 4자리 코드, {@link #RESUME_CONFIRM_WINDOW}(60초)·일회용</li>
 *   <li>resume 확인 — 대기 코드와 비교 후 맞든 틀리든 폐기, 일치하면 killSwitch.release("telegram")</li>
 *   <li>status — 킬스위치·보유 종목 요약</li>
 * </ul>
 * 거래·주문 권한은 없다(킬스위치 작동/해제와 조회뿐). 응답 문구는 돌려줄 뿐 보내지 않는다 — 보내는 쪽은 호출자다.
 */
@Component
public class TelegramStockCommands {

    private static final Logger log = LoggerFactory.getLogger(TelegramStockCommands.class);

    /** /resume 확인 코드 유효 시간. */
    static final Duration RESUME_CONFIRM_WINDOW = Duration.ofSeconds(60);

    /**
     * 명령 결과. {@code legacyNotice}는 기존 poller가 직접 알림으로 보내던 경우의 수준이다 — null이면 기존 poller는 따로
     * 알리지 않았다(작동·해제는 KillSwitchChanged 알림이 대신한다). {@code reply}는 플랫폼 경로의 응답 본문이며 비어 있지 않다.
     */
    public record Reply(boolean succeeded, String code, String reply, NoticeLevel legacyNotice) {
        public Reply {
            Objects.requireNonNull(code);
            if (reply == null || reply.isBlank()) {
                throw new IllegalArgumentException("stock-command-reply-required");
            }
        }
        @Override public String toString() { return "StockCommandReply[code=" + code + "]"; }
    }

    private record PendingResume(String code, Instant expiresAt) {
    }

    private final KillSwitch killSwitch;
    private final PositionBook positionBook;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final AtomicReference<PendingResume> pendingResume = new AtomicReference<>();

    public TelegramStockCommands(KillSwitch killSwitch, PositionBook positionBook, Clock clock) {
        this.killSwitch = Objects.requireNonNull(killSwitch);
        this.positionBook = Objects.requireNonNull(positionBook);
        this.clock = Objects.requireNonNull(clock);
    }

    /** 1단계(안전 방향이라 즉시). */
    public Reply stop() {
        boolean already = killSwitch.isEngaged();
        killSwitch.engage("텔레그램 원격 명령");
        return already
                ? new Reply(true, "kill-switch-already-engaged", "킬스위치는 이미 작동 중입니다(신규 주문 차단).", null)
                : new Reply(true, "kill-switch-engaged", "킬스위치를 작동했습니다 — 신규 주문을 차단합니다.", null);
    }

    /** 해제 확인 코드를 발급한다(해제는 아직 안 한다). */
    public Reply requestResume() {
        if (!killSwitch.isEngaged()) {
            pendingResume.set(null);
            return new Reply(false, "kill-switch-not-engaged", "킬스위치는 이미 해제 상태입니다(주문 허용).", NoticeLevel.INFO);
        }
        String code = "%04d".formatted(random.nextInt(10_000));
        pendingResume.set(new PendingResume(code, clock.instant().plus(RESUME_CONFIRM_WINDOW)));
        return new Reply(true, "resume-code-issued", ("킬스위치 해제 확인 — %d초 안에 /resume %s 를 보내면 해제합니다. "
                + "해제하면 신규 주문이 다시 허용됩니다. 작동 원인을 확인했나요?")
                .formatted(RESUME_CONFIRM_WINDOW.toSeconds(), code), NoticeLevel.WARN);
    }

    /** 코드가 맞고 만료 전이면 해제한다. 코드는 한 번 쓰면 버린다(맞든 틀리든 — 추측 반복 차단). */
    public Reply confirmResume(String code) {
        PendingResume pending = pendingResume.getAndSet(null);
        if (pending == null || clock.instant().isAfter(pending.expiresAt())) {
            return new Reply(false, "resume-code-missing-or-expired",
                    "해제 확인 코드가 없거나 만료됐습니다 — /resume 부터 다시 보내세요. (해제하지 않았음)", NoticeLevel.WARN);
        }
        if (!pending.code().equals(code)) {
            log.warn("텔레그램 /resume 확인 코드 불일치 — 해제하지 않음");
            return new Reply(false, "resume-code-mismatch",
                    "확인 코드가 맞지 않습니다 — 해제하지 않았습니다. /resume 부터 다시 보내세요.", NoticeLevel.WARN);
        }
        killSwitch.release("telegram");
        return new Reply(true, "kill-switch-released", "킬스위치를 해제했습니다 — 신규 주문이 다시 허용됩니다.", null);
    }

    /** 포지션 개수/목록 + 킬스위치 상태. */
    public Reply status() {
        var positions = positionBook.snapshot();
        StringBuilder sb = new StringBuilder();
        sb.append("킬스위치: ").append(killSwitch.isEngaged() ? "작동 중" : "정상").append('\n');
        sb.append("보유 종목 수: ").append(positions.size());
        positions.forEach((symbol, position) ->
                sb.append("\n- ").append(StockNames.label(symbol)).append(' ')
                        .append(position.quantity()).append("주 @ ").append(position.avgPriceText()));
        return new Reply(true, "status", sb.toString(), NoticeLevel.INFO);
    }

    /** 수신 이관 정지 때 대기 중인 확인 코드를 폐기한다. 폐기한 코드가 있었으면 true. */
    boolean dropPendingResume() {
        return pendingResume.getAndSet(null) != null;
    }
}

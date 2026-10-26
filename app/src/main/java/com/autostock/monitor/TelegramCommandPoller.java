package com.autostock.monitor;

import com.autostock.common.util.StockNames;
import com.autostock.risk.KillSwitch;
import com.autostock.portfolio.PositionBook;
import com.autostock.common.util.SecretMasking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 텔레그램 원격 명령 — 폴링(long polling, {@code getUpdates}) 방식.
 *
 * <p><b>왜 웹훅이 아니라 폴링인가</b>: 텔레그램 웹훅은 봇 서버가 공인 IP(+ HTTPS 인증서)를
 * 가지고 있어야 텔레그램이 이벤트를 밀어줄 수 있다. 이 프로젝트는 가정용 회선(자택망)에서
 * 도는 개인 운영 환경이라 공인 IP를 안정적으로 확보하기 어렵다 — 그래서 봇이 능동적으로
 * 주기적으로 물어보는(polling) 방식을 쓴다. 대신 5초 지연이 생기지만, 킬스위치처럼
 * "즉시 반응"이 필수는 아닌 용도라 감내 가능하다고 판단했다.
 *
 * <p><b>보안</b>: 이 봇 토큰을 아는 사람은 누구나 메시지를 보낼 수 있다(텔레그램 자체는
 * 발신자를 막지 않는다). 그래서 설정된 {@code monitor.telegram.chat-id}와 일치하는
 * chat_id의 메시지만 처리하고, 나머지는 전부 무시한다({@link #parseCommand} 참고) —
 * 타인이 봇 이름을 알아내 "/stop"을 보내도 아무 일도 일어나지 않는다.
 *
 * <p>명령:
 * <ul>
 *   <li>{@code /stop} — killSwitch.engage("텔레그램 원격 명령") — 1단계(안전 방향이라 즉시)</li>
 *   <li>{@code /resume} — <b>2단계 확인</b>(Phase 0.7, D-09, aiDoc/heartbeat-telegram.md): 무작위 4자리 코드를 답장으로
 *       보내고, {@link #RESUME_CONFIRM_WINDOW}(60초) 안에 {@code /resume 1234}가 오면 killSwitch.release("telegram").
 *       코드는 한 번 쓰면 끝(맞든 틀리든) — 잘못 누른 /resume 한 번으로 비상 정지가 풀리지 않게 한다(PLAN A3 "위험 명령 2단계 확인").</li>
 *   <li>{@code /status} — 포지션·킬스위치 상태 요약을 다시 알림으로 보냄</li>
 * </ul>
 *
 * <p>TODO 실측(자택망): {@code getUpdates} 응답의 정확한 JSON 구조(특히 message가 아닌
 * edited_message/channel_post 등 다른 update 종류가 섞여 올 때의 처리)는 텔레그램 공식
 * 문서 기반 추정이다. 실제 봇으로 명령을 보내보고 파싱 로직을 검증해야 한다.
 */
@Component
@ConditionalOnProperty(prefix = "monitor.telegram", name = "enabled", havingValue = "true")
public class TelegramCommandPoller {

    private static final Logger log = LoggerFactory.getLogger(TelegramCommandPoller.class);

    /** parseCommand의 판정 결과. */
    public enum Command {
        STOP,
        /** 해제 요청 — 확인 코드를 발급한다(아직 해제하지 않는다). */
        RESUME,
        /** {@code /resume 1234} — 확인 코드로 해제를 확정한다. */
        RESUME_CONFIRM,
        STATUS,
        /** chat_id 불일치, 미지원 텍스트, 빈 메시지 등 — 아무 동작도 하지 않는다. */
        IGNORED
    }

    /** /resume 확인 코드 유효 시간. */
    static final Duration RESUME_CONFIRM_WINDOW = Duration.ofSeconds(60);

    private static final Pattern RESUME_CONFIRM_PATTERN = Pattern.compile("/resume\\s+(\\d{4})");

    /** 발급한 해제 확인 코드와 만료 시각. */
    private record PendingResume(String code, Instant expiresAt) {
    }

    private final WebClient webClient;
    private final TelegramProperties properties;
    private final KillSwitch killSwitch;
    private final PositionBook positionBook;
    private final Notifier notifier;
    private final Clock clock;
    private PlatformTelegramBridge platformBridge;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void platformBridge(PlatformTelegramBridge bridge){this.platformBridge=bridge;}

    /**
     * 수신자 이관 정지(VALUNIVO R16, aiDoc/telegram-receiver-stop-signal-design.md) — autostock.telegram.handover.enabled=true일 때만
     * 빈이 있다. 없으면 기존 동작 그대로. 있으면 활성 정지 기록이 재기동 뒤에도 폴링을 막는다.
     */
    private TelegramReceiverStopStore stopStore;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void receiverStopStore(TelegramReceiverStopStore store){this.stopStore=store;}
    /** 폴링 한 회차와 이관 정지가 겹치지 않게 — 진행 중인 묶음을 끝까지 처리한 뒤에만 정지한다. */
    private final ReentrantLock pollLock = new ReentrantLock();
    /** null=아직 DB 확인 전. 확인 실패 때는 그 회차를 건너뛴다(정지 기록을 모르고 두 번째 수신자가 되지 않게). */
    private volatile Boolean handoverStopped;
    private final SecureRandom random = new SecureRandom();
    private final AtomicReference<PendingResume> pendingResume = new AtomicReference<>();

    /** 다음 getUpdates 호출에 쓸 offset — 이 값 미만의 update는 이미 처리했다고 텔레그램에 알리는 것. */
    private final AtomicLong nextOffset = new AtomicLong(0);

    /** 연속 폴링 실패(L-6) — 로그 요약과 재시도 간격. */
    private final FailureStreak failures = new FailureStreak();

    public TelegramCommandPoller(WebClient.Builder builder,
                                 TelegramProperties properties,
                                 KillSwitch killSwitch,
                                 PositionBook positionBook,
                                 Notifier notifier,
                                 Clock clock) {
        this.webClient = builder.baseUrl("https://api.telegram.org").build();
        this.properties = properties;
        this.killSwitch = killSwitch;
        this.positionBook = positionBook;
        this.notifier = notifier;
        this.clock = clock;
    }

    /**
     * 5초 주기 폴링. enabled=false면 빈 자체가 등록되지 않지만, 방어적으로 한 번 더 확인한다.
     * {@code fixedDelay}(직전 실행 종료 기준, Phase 0.7) — fixedRate는 PC 절전 복귀 때 밀린 회차를 가상 스레드로
     * 동시에 몰아 실행한다(StaleOrderCanceller 설명, 2026-09-22 풀 고갈 실측과 같은 이유). 같은 update를 동시에
     * 처리해 /stop·/resume이 두 번 실행될 여지도 없앤다.
     */
    @Scheduled(fixedDelay = 5_000)
    public void poll() {
        if (!properties.enabled()) {
            return;
        }
        if (stopStore == null) {
            pollOnce();
            return;
        }
        pollLock.lock();
        try {
            if (handoverAllowsPolling()) {
                pollOnce();
            }
        } finally {
            pollLock.unlock();
        }
    }

    private boolean handoverAllowsPolling() {
        if (handoverStopped == null) {
            try {
                handoverStopped = stopStore.active().isPresent();
            } catch (RuntimeException e) {
                log.warn("텔레그램 수신 이관 정지 기록 확인 실패 — 이번 회차 폴링을 건너뜀");
                return false;
            }
        }
        return !handoverStopped;
    }

    /**
     * 이관 정지 — 진행 중인 회차가 끝나기를 기다리고, 처리한 update만 텔레그램에 확인(offset=nextOffset, limit=1, timeout=0;
     * 응답으로 온 새 update는 처리하지 않고 버려 미확인으로 남긴다), 대기 중인 /resume 확인 코드를 폐기한 뒤 불변 정지 기록을 남긴다.
     * 확인 호출이나 기록이 실패하면 정지하지 않은 상태로 되돌린다(긴급 /stop 경로 유지). 운영자 명시 호출 전용 — Telegram 명령 아님.
     */
    public TelegramReceiverStopStore.Stop stopForHandover(String instanceId) {
        return stopForHandover(instanceId, () -> {});
    }

    /** Source-only operator hook: recheck the private claim after waiting for the polling round.
     * The callback is not authentication or one-shot dispatch; the caller must supply those separately.
     * Rejection occurs before confirmation, pending-code disposal or a stop record.
     */
    public TelegramReceiverStopStore.Stop stopForHandover(String instanceId, Runnable beforeConfirmation) {
        Objects.requireNonNull(beforeConfirmation, "handover guard required");
        if (stopStore == null) {
            throw new IllegalStateException("telegram-handover-disabled");
        }
        pollLock.lock();
        try {
            if (!handoverAllowsPolling()) {
                throw new IllegalStateException(handoverStopped == null ? "telegram-handover-state-unavailable" : "telegram-receiver-already-stopped");
            }
            beforeConfirmation.run();
            handoverStopped = true;
            try {
                confirmProcessed(nextOffset.get());
                boolean dropped = pendingResume.getAndSet(null) != null;
                return stopStore.recordStopped(nextOffset.get(), dropped, instanceId);
            } catch (RuntimeException e) {
                handoverStopped = false;
                throw new IllegalStateException("telegram-handover-stop-failed");
            }
        } finally {
            pollLock.unlock();
        }
    }

    /** 이관 취소 — 플랫폼이 수신권을 잡지 않았음을 운영자가 확인한 뒤에만. 철회 행을 추가하고 폴링을 재개한다. */
    public void resumeAfterWithdraw(java.util.UUID stopId, String instanceId) {
        if (stopStore == null) {
            throw new IllegalStateException("telegram-handover-disabled");
        }
        pollLock.lock();
        try {
            stopStore.withdraw(stopId, instanceId);
            handoverStopped = false;
        } finally {
            pollLock.unlock();
        }
    }

    private void confirmProcessed(long offset) {
        Map<?, ?> response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/bot{token}/getUpdates")
                        .queryParam("offset", offset)
                        .queryParam("limit", 1)
                        .queryParam("timeout", 0)
                        .build(properties.botToken()))
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofSeconds(10));
        if (response == null || !Boolean.TRUE.equals(response.get("ok"))) {
            throw new IllegalStateException("telegram-handover-confirm-failed");
        }
    }

    private void pollOnce() {
        Instant now = clock.instant();
        if (failures.shouldWait(now)) {
            return;   // 연속 실패 중 — 간격을 벌려 다시 본다
        }
        List<Map<String, Object>> updates;
        try {
            updates = fetchUpdates();
        } catch (Exception e) {
            // 폴링 한 번 실패해도(네트워크 순단 등) 다음 주기에 재시도된다 — 예외를 삼킨다.
            // 예외 메시지에 봇 토큰이 담긴 전체 URL(/bot{token}/getUpdates)이 그대로 들어있을
            // 수 있어(WebClientResponseException 등) 마스킹 후 로그로 남긴다 — SecretMasking 참고.
            // 실패마다 ERROR와 스택을 남기면 망이 끊긴 동안 로그가 묻힌다(10/13 13:50~16:00 81건, L-6) — 처음 한 번과
            // 10분마다 요약 한 줄, 복구 때 한 줄만 남긴다(FailureStreak).
            String line = failures.fail(now, SecretMasking.mask(describe(e)));
            if (line != null) {
                log.warn(line);
            }
            return;
        }
        String recovered = failures.succeed(now);
        if (recovered != null) {
            log.info(recovered);
        }
        for (Map<String, Object> update : updates) {
            handleUpdate(update);
        }
    }

    /**
     * 연속 실패 기록(L-6, 2026-10-13) — 처음 실패는 한 줄, 그 뒤로는 {@link #SUMMARY_EVERY}마다 요약 한 줄, 복구되면 한 줄.
     * 실패가 이어지면 다음 시도까지 간격을 벌린다(두 번째 실패부터 15초·30초·60초 상한) — 망이 끊긴 동안 5초마다 연결
     * 타임아웃(5초)을 기다리지 않게. 폴링은 한 번에 하나씩 돈다(fixedDelay)라 동기화가 필요 없다.
     */
    static final class FailureStreak {
        static final Duration SUMMARY_EVERY = Duration.ofMinutes(10);
        private static final Duration MAX_WAIT = Duration.ofSeconds(60);

        private int count;
        private Instant since;
        private Instant lastFailure;
        private Instant lastLogged;

        /** 지금은 건너뛸 차례인가 — 연속 실패 뒤 간격(클래스 설명). */
        boolean shouldWait(Instant now) {
            return count >= 2 && now.isBefore(lastFailure.plus(backoff()));
        }

        Duration backoff() {
            if (count < 2) {
                return Duration.ZERO;
            }
            long seconds = 15L << Math.min(count - 2, 3);
            return seconds >= MAX_WAIT.toSeconds() ? MAX_WAIT : Duration.ofSeconds(seconds);
        }

        /** 실패 한 번 — 남길 로그 문구, 이번에는 남기지 않으면 null. */
        String fail(Instant now, String reason) {
            count++;
            lastFailure = now;
            if (count == 1) {
                since = now;
                lastLogged = now;
                return "텔레그램 명령 폴링 실패 — " + reason + ". 이어지는 실패는 10분마다 한 줄로 요약하고, 복구되면 알린다";
            }
            if (!now.isBefore(lastLogged.plus(SUMMARY_EVERY))) {
                lastLogged = now;
                return "텔레그램 명령 폴링 계속 실패 — %d회째(%s부터), 마지막: %s".formatted(count, kst(since), reason);
            }
            return null;
        }

        /** 성공 — 실패 중이었으면 복구 문구, 아니면 null. */
        String succeed(Instant now) {
            if (count == 0) {
                return null;
            }
            String line = "텔레그램 명령 폴링 복구 — %d회 실패(%s~%s, %d분)".formatted(count, kst(since), kst(now),
                    Duration.between(since, now).toMinutes());
            count = 0;
            since = null;
            lastFailure = null;
            lastLogged = null;
            return line;
        }

        private static String kst(Instant instant) {
            return java.time.LocalTime.ofInstant(instant, com.autostock.common.util.MarketConstants.KST)
                    .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        }
    }

    /**
     * 실패 사유 한 줄 — 메시지가 없으면 근본 원인 이름을 쓴다. 10/15 08:24 읽기 시간 초과가
     * "WebClientRequestException: null"로만 남아 무슨 실패인지 알 수 없었다(원인은 netty ReadTimeoutException).
     */
    static String describe(Throwable e) {
        String head = e.getClass().getSimpleName();
        String message = e.getMessage();
        if (message != null && !message.isBlank() && !"null".equals(message)) {
            return head + ": " + message;
        }
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root == e) {
            return head;
        }
        String rootMessage = root.getMessage();
        return head + ": " + root.getClass().getSimpleName()
                + (rootMessage == null || rootMessage.isBlank() ? "" : " " + rootMessage);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchUpdates() {
        Map<String, Object> response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/bot{token}/getUpdates")
                        .queryParam("offset", nextOffset.get())
                        .queryParam("timeout", 0) // short polling — @Scheduled 주기 자체가 대기 역할
                        .build(properties.botToken()))
                .retrieve()
                .bodyToMono(Map.class)
                .block();
        if (response == null) {
            return List.of();
        }
        Object result = response.get("result");
        return result instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    /** update 1건 처리 — 패키지 공개는 테스트가 응답 없이 update를 바로 흘려 넣기 위해서다(2단계 확인 검증). */
    @SuppressWarnings("unchecked")
    void handleUpdate(Map<String, Object> update) {
        // update_id를 확인했으면 다음 폴링부터는 이 이하 offset은 다시 받지 않는다
        // (텔레그램 Bot API 표준 패턴 — getUpdates(offset=마지막 update_id+1)).
        if (update.get("update_id") instanceof Number updateId) {
            nextOffset.set(updateId.longValue() + 1);
        }

        Object messageObj = update.get("message");
        if (!(messageObj instanceof Map)) {
            return; // edited_message 등 다른 update 종류는 명령으로 취급하지 않는다
        }
        Map<String, Object> message = (Map<String, Object>) messageObj;
        Object chatObj = message.get("chat");
        String chatId = chatObj instanceof Map<?, ?> chat && chat.get("id") != null
                ? String.valueOf(chat.get("id"))
                : null;
        String text = message.get("text") instanceof String s ? s : null;

        Command command = parseCommand(chatId, text, properties.chatId());
        switch (command) {
            case STOP -> killSwitch.engage("텔레그램 원격 명령");
            case RESUME -> requestResumeConfirmation();
            case RESUME_CONFIRM -> confirmResume(confirmCodeOf(text));
            case STATUS -> notifier.notify(NoticeLevel.INFO, buildStatusSummary());
            case IGNORED -> {
                if(platformBridge!=null)platformBridge.accept(update);
                if (chatId != null && !chatId.equals(properties.chatId())) {
                    // 보안 감사 로그 — 등록되지 않은 chat_id가 명령을 시도했다는 흔적을 남긴다.
                    log.warn("등록되지 않은 chat_id의 텔레그램 명령 무시: chatId={}", chatId);
                }
            }
        }
    }

    /**
     * 순수 함수로 분리한 명령 파서 — 네트워크/Spring 컨텍스트 없이 단위테스트 가능.
     *
     * @param senderChatId  메시지를 보낸 chat_id (텔레그램 응답에서 추출, 없으면 null)
     * @param text          메시지 본문 (없으면 null)
     * @param allowedChatId 설정에 등록된 유일한 허용 chat_id
     */
    static Command parseCommand(String senderChatId, String text, String allowedChatId) {
        if (allowedChatId == null || allowedChatId.isBlank()
                || !Objects.equals(allowedChatId, senderChatId)) {
            return Command.IGNORED; // 보안: 등록된 chat_id가 아니면 무조건 무시
        }
        if (text == null) {
            return Command.IGNORED;
        }
        String stripped = text.strip();
        if (RESUME_CONFIRM_PATTERN.matcher(stripped).matches()) {
            return Command.RESUME_CONFIRM;
        }
        return switch (stripped) {
            case "/stop" -> Command.STOP;
            case "/resume" -> Command.RESUME;
            case "/status" -> Command.STATUS;
            default -> Command.IGNORED;
        };
    }

    /** {@code /resume 1234}의 코드 부분. 형식이 아니면 null. */
    static String confirmCodeOf(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = RESUME_CONFIRM_PATTERN.matcher(text.strip());
        return matcher.matches() ? matcher.group(1) : null;
    }

    /** 1단계: 해제 확인 코드를 발급해 답장한다(해제는 아직 안 한다). */
    private void requestResumeConfirmation() {
        if (!killSwitch.isEngaged()) {
            pendingResume.set(null);
            notifier.notify(NoticeLevel.INFO, "킬스위치는 이미 해제 상태입니다(주문 허용).");
            return;
        }
        String code = "%04d".formatted(random.nextInt(10_000));
        pendingResume.set(new PendingResume(code, clock.instant().plus(RESUME_CONFIRM_WINDOW)));
        notifier.notify(NoticeLevel.WARN, ("킬스위치 해제 확인 — %d초 안에 /resume %s 를 보내면 해제합니다. "
                + "해제하면 신규 주문이 다시 허용됩니다. 작동 원인을 확인했나요?")
                .formatted(RESUME_CONFIRM_WINDOW.toSeconds(), code));
    }

    /** 2단계: 코드가 맞고 만료 전이면 해제한다. 코드는 한 번 쓰면 버린다(맞든 틀리든 — 추측 반복 차단). */
    private void confirmResume(String code) {
        PendingResume pending = pendingResume.getAndSet(null);
        if (pending == null || clock.instant().isAfter(pending.expiresAt())) {
            notifier.notify(NoticeLevel.WARN, "해제 확인 코드가 없거나 만료됐습니다 — /resume 부터 다시 보내세요. (해제하지 않았음)");
            return;
        }
        if (!pending.code().equals(code)) {
            log.warn("텔레그램 /resume 확인 코드 불일치 — 해제하지 않음");
            notifier.notify(NoticeLevel.WARN, "확인 코드가 맞지 않습니다 — 해제하지 않았습니다. /resume 부터 다시 보내세요.");
            return;
        }
        killSwitch.release("telegram");
    }

    /** /status 응답 본문 — 포지션 개수/목록 + 킬스위치 상태. */
    private String buildStatusSummary() {
        var positions = positionBook.snapshot();
        StringBuilder sb = new StringBuilder();
        sb.append("킬스위치: ").append(killSwitch.isEngaged() ? "작동 중" : "정상").append('\n');
        sb.append("보유 종목 수: ").append(positions.size());
        positions.forEach((symbol, position) ->
                sb.append("\n- ").append(StockNames.label(symbol)).append(' ')
                        .append(position.quantity()).append("주 @ ").append(position.avgPriceText()));
        return sb.toString();
    }
}

package com.autostock.kiwoom;

import com.autostock.common.event.BrokerAuthFailure;
import com.autostock.common.util.SecretMasking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 접근토큰 관리자 — 키움 API의 "출입증"을 발급받고 갱신한다.
 *
 * <p>키움 REST API 인증 흐름:
 * <pre>
 *   앱키 + 시크릿  ──(POST /oauth2/token)──▶  접근토큰 (약 24시간 유효)
 *   이후 모든 API 호출 헤더에:  authorization: Bearer {접근토큰}
 * </pre>
 *
 * <p>설계 포인트:
 * <ul>
 *   <li><b>캐싱</b>: 토큰 발급도 API 호출이므로 매번 발급받으면 낭비 + rate limit 소모.
 *       발급받은 토큰을 저장해 두고 재사용한다.</li>
 *   <li><b>선제 갱신</b>: 만료 "5분 전"부터 새 토큰을 받는다. 만료 직전까지 쓰다가
 *       장중에 인증 실패가 나는 상황을 피하기 위해서다.</li>
 *   <li><b>{@link AtomicReference}</b>: 여러 스레드(WS, 스케줄러, 주문)가 동시에
 *       토큰을 요구해도 안전하게 교체하기 위한 장치.</li>
 *   <li><b>single-flight 발급</b>: 캐시가 비어있거나 만료된 시점에 여러 스레드가 동시에
 *       {@link #accessToken()}을 부르면(실측 2026-09-11: WS start()의 connect()와 watchdog의
 *       첫 틱이 거의 동시에 실행되는 케이스), 잠금 없이 각자 issue()를 부르면 POST /oauth2/token이
 *       같은 밀리초에 중복 발행되어 두 번째 요청이 429를 받는다. {@link #issue()}를
 *       {@link ReentrantLock}으로 감싸 동시 호출을 한 번의 실제 발급으로 합치고(single-flight),
 *       락을 놓친 스레드는 락 획득 후 캐시를 다시 확인해 이미 갱신된 토큰을 재사용한다(더블체크).
 *       synchronized가 아니라 ReentrantLock인 이유: JDK21 가상 스레드에서 synchronized 블록 안의
 *       블로킹 I/O(webClient.block())는 캐리어 스레드를 고정(pinning)시키는 문제가 있다
 *       (execution.BrokerEquitySource, monitor.EventFeed와 동일한 근거 — ADR-5).</li>
 *   <li><b>서버 거부 시 폐기</b>({@link #invalidate(String)}, 2026-09-30): 만료 전인데도 서버가
 *       {@code [8005:Token이 유효하지 않습니다]}로 거부하면 캐시를 버려 다음 호출이 재발급하게 한다.
 *       실측: 07:12 발급 토큰이 PC 절전 복귀(17:51) 직후 8005로 거부됐고, 캐시상 유효라 재발급 없이
 *       일일 리포트(kt00018)·분봉 적재(ka10080)가 전부 실패했다.</li>
 *   <li><b>발급 실패 알림</b>(Phase 0.6, aiDoc/kiwoom-error-codes.md): 발급 응답에 인증 계열 오류코드(8001·8002·8010·
 *       8030·8031·8040·8050·8103)가 있으면 {@link BrokerAuthFailure}를 발행한다 — REST·WS 어느 경로의 발급이든
 *       여기 한 곳에서 잡힌다. 흔한 원인은 허용 IP 미등록·App Key 해지(3개월 실서버 미접속)다.</li>
 * </ul>
 */
@Component
public class TokenManager {

    private static final Logger log = LoggerFactory.getLogger(TokenManager.class);

    /**
     * 키움 토큰 발급 응답의 {@code expires_dt} 포맷 — 실측(2026-08-13, mockapi.kiwoom.com):
     * {@code "20260814121649"} 같은 14자리 문자열, KST(Asia/Seoul) 기준 로컬 시각이다.
     * (타임존 표기가 응답에 없으므로 코드로 고정해야 한다 — 서버가 UTC로 도는 환경에서
     * 이 가정이 빠지면 만료시각이 9시간 어긋난다.)
     */
    private static final DateTimeFormatter EXPIRES_DT_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final WebClient webClient;
    private final KiwoomProperties properties;
    private final ApplicationEventPublisher publisher;

    /** 현재 유효한 토큰 캐시. null이면 아직 한 번도 발급받지 않은 상태. */
    private final AtomicReference<CachedToken> cached = new AtomicReference<>();

    /** 마지막 발급 시도 결과 — 헬스 {@code kiwoomAuth}(실행 계획 1.7)가 읽는다. 토큰 값은 담지 않는다. */
    private volatile IssueStatus lastIssue;

    /** issue() 동시 호출을 한 번의 실제 발급으로 합치기 위한 락(single-flight). 클래스 Javadoc 참고. */
    private final ReentrantLock issueLock = new ReentrantLock();
    private final Clock clock;

    /**
     * 발급 직후 이 시간 안에 거부된 토큰은 폐기하지 않는다 — 막 받은 토큰까지 거부된다면 원인은 토큰이
     * 아니라(키 오류·서버 점검 등) 재발급해도 소용없고, 거부될 때마다 재발급하면 토큰 발급 호출만 쌓인다.
     * 결과적으로 재발급은 최대 1분에 한 번으로 묶인다.
     */
    static final Duration MIN_AGE_TO_INVALIDATE = Duration.ofSeconds(60);

    public TokenManager(WebClient.Builder builder, KiwoomProperties properties,
                        ApplicationEventPublisher publisher, Clock clock) {
        this.clock = clock;
        this.properties = properties;
        this.publisher = publisher;
        this.webClient = builder.baseUrl(properties.restBaseUrl()).build();
    }

    /**
     * 유효한 접근토큰을 반환한다. 없거나 곧 만료되면 새로 발급받는다.
     * 호출자는 만료 걱정 없이 그냥 이 메서드만 부르면 된다.
     */
    public String accessToken() {
        CachedToken token = cached.get();
        if (token == null || token.expiresSoon(clock.instant())) {
            // 캐시가 없거나 곧 만료 — 락을 잡고 발급한다(single-flight). 락을 기다리는 동안
            // 다른 스레드가 이미 발급을 끝냈을 수 있으므로, 락 획득 직후 캐시를 다시 확인한다
            // (더블체크) — 그렇지 않으면 락이 풀릴 때마다 스레드 수만큼 중복 발급(429 원인)된다.
            issueLock.lock();
            try {
                token = cached.get();
                if (token == null || token.expiresSoon(clock.instant())) {
                    token = issue();
                    cached.set(token);
                }
            } finally {
                issueLock.unlock();
            }
        }
        return token.value();
    }

    /**
     * 서버가 거부한 토큰을 캐시에서 버린다 — 다음 {@link #accessToken()} 호출이 새로 발급한다.
     *
     * <p>거부된 토큰이 지금 캐시와 같을 때만 버린다: 여러 스레드가 같은 거부를 동시에 보고해도
     * 이미 다른 스레드가 재발급한 새 토큰을 지우지 않는다. 발급 후 {@link #MIN_AGE_TO_INVALIDATE}가
     * 지나지 않은 토큰도 버리지 않는다(필드 설명 참고).
     *
     * @param rejectedToken 서버가 거부한 요청에 실었던 토큰
     */
    public void invalidate(String rejectedToken) {
        CachedToken current = cached.get();
        if (current == null || !current.value().equals(rejectedToken)) {
            return;     // 이미 다른 스레드가 폐기·재발급했다
        }
        if (current.issuedAt().plus(MIN_AGE_TO_INVALIDATE).isAfter(clock.instant())) {
            log.warn("방금 발급한 접근토큰이 거부됨 — 토큰 문제가 아닌 것으로 보고 폐기하지 않는다");
            return;
        }
        if (cached.compareAndSet(current, null)) {
            log.warn("접근토큰이 만료 전에 서버에서 거부됨 — 캐시 폐기, 다음 호출에서 재발급");
        }
    }

    /** 키움에 토큰 발급을 요청한다. 실패 시 예외 — 호출자(재시도 로직)가 처리. 결과는 {@link #lastIssueStatus()}에 남는다. */
    private CachedToken issue() {
        log.info("접근토큰 발급 요청");
        Map<String, Object> response;
        try {
            response = callApi();
        } catch (WebClientResponseException e) {
            // HTTP 오류 본문의 return_msg에 인증 코드가 있으면 알린다. 예외 자체는 예전과 같이 그대로 던진다
            // (주문 경로에서 결과 불명 처리 등 기존 분류를 바꾸지 않는다).
            String code = reportAuthFailure(e.getResponseBodyAsString()).orElse("HTTP " + e.getStatusCode().value());
            recordFailure(code);
            throw e;
        } catch (RuntimeException e) {
            recordFailure(e.getClass().getSimpleName());
            throw e;
        }
        // HTTP 200이어도 return_code != 0이면 논리 오류(키/시크릿 오류 등) — 실측 응답 포맷:
        // {"expires_dt":"20260814121649","return_msg":"...","token_type":"Bearer","return_code":0,"token":"..."}
        // return_code를 토큰 유무보다 먼저 본다 — 실패 응답에는 토큰이 없어 "응답 없음"으로 뭉개지면 원인 코드를 잃는다.
        Object returnCode = response == null ? null : response.get("return_code");
        if (returnCode != null && ((Number) returnCode).intValue() != 0) {
            String message = String.valueOf(response.get("return_msg"));
            recordFailure(reportAuthFailure(message).orElse("return_code " + returnCode));
            throw new KiwoomTokenIssueException("토큰 발급 실패: " + message);
        }
        if (response == null || response.get("token") == null) {
            recordFailure("응답 없음");
            throw new KiwoomTokenIssueException("토큰 발급 실패: 응답 없음");
        }
        CachedToken token = new CachedToken((String) response.get("token"), parseExpiresAt(response), clock.instant());
        lastIssue = new IssueStatus(token.issuedAt(), true, null, token.expiresAt());
        return token;
    }

    private void recordFailure(String failure) {
        lastIssue = new IssueStatus(clock.instant(), false, failure, null);
    }

    /**
     * 발급 실패 원문에 인증 계열 코드가 있으면 {@link BrokerAuthFailure}를 발행한다(클래스 설명 "발급 실패 알림").
     *
     * @return 찾은 인증 오류코드(없으면 빈 값)
     */
    private Optional<String> reportAuthFailure(String responseText) {
        Optional<String> found = KiwoomErrorCodes.find(responseText, KiwoomErrorCodes.AUTH_FAILURE);
        found.ifPresent(code -> {
            String host = URI.create(properties.restBaseUrl()).getHost();
            log.error("키움 접근토큰 발급 인증 실패 [{}] host={} — 허용 IP·App Key 상태 확인 필요, 긴급 알림 발행", code, host);
            publisher.publishEvent(new BrokerAuthFailure(code, SecretMasking.mask(responseText), host, clock.instant()));
        });
        return found;
    }

    /** 마지막 발급 시도 결과. 아직 시도한 적이 없으면 빈 값. */
    public Optional<IssueStatus> lastIssueStatus() {
        return Optional.ofNullable(lastIssue);
    }

    /**
     * 토큰 발급 시도 결과 — 헬스 표시용(토큰 값은 없다).
     *
     * @param at        시도 시각
     * @param success   성공 여부
     * @param failure   실패 사유 코드(예: 8030, HTTP 503, return_code 3) — 성공이면 null
     * @param expiresAt 발급받은 토큰의 만료 시각 — 실패면 null
     */
    public record IssueStatus(Instant at, boolean success, String failure, Instant expiresAt) {
    }

    /**
     * 실제 HTTP 호출 지점 — protected로 열어 두어 테스트에서 이 메서드를 오버라이드해
     * (지연을 주거나 호출 횟수를 세는 등) 실제 네트워크 없이 single-flight 동작을 검증할 수 있게
     * 했다({@code macrointel.FredClient.callApi}와 같은 패턴).
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> callApi() {
        return webClient.post()
                .uri("/oauth2/token")
                .header("api-id", TrId.TOKEN_ISSUE.apiId())
                .contentType(MediaType.valueOf("application/json;charset=UTF-8"))
                .bodyValue(Map.of(
                        "grant_type", "client_credentials",   // 고정값: 서버 간 인증 방식
                        "appkey", properties.appKey(),
                        "secretkey", properties.appSecret()))
                .retrieve()
                .bodyToMono(Map.class)
                .block();                                     // 동기 대기 — 토큰 없인 아무것도 못 하므로
    }

    /**
     * 응답의 {@code expires_dt}(yyyyMMddHHmmss, KST)를 파싱해 만료 시각(Instant)으로 변환한다.
     * 필드가 없는 예외적인 응답이면 보수적으로 23시간 유효(실제 유효기간 약 24시간)로 가정한다.
     */
    private Instant parseExpiresAt(Map<String, Object> response) {
        Object expiresDt = response.get("expires_dt");
        if (expiresDt == null || String.valueOf(expiresDt).isBlank()) {
            log.warn("토큰 응답에 expires_dt가 없어 23시간 유효로 보수적 가정함");
            return clock.instant().plusSeconds(23 * 3600);
        }
        return LocalDateTime.parse(String.valueOf(expiresDt), EXPIRES_DT_FORMAT)
                .atZone(KST)
                .toInstant();
    }

    /**
     * 토큰 + 만료시각 묶음.
     *
     * @param value     토큰 문자열
     * @param expiresAt 만료 시각
     * @param issuedAt  발급받은 시각 — {@link #invalidate(String)}의 최소 수명 판단용
     */
    private record CachedToken(String value, Instant expiresAt, Instant issuedAt) {

        /** 만료 5분 전부터 true — 선제 갱신 트리거. */
        boolean expiresSoon(Instant now) {
            return now.plusSeconds(300).isAfter(expiresAt);
        }
    }
}

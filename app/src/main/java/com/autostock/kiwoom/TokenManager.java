package com.autostock.kiwoom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
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

    /** 현재 유효한 토큰 캐시. null이면 아직 한 번도 발급받지 않은 상태. */
    private final AtomicReference<CachedToken> cached = new AtomicReference<>();

    /** issue() 동시 호출을 한 번의 실제 발급으로 합치기 위한 락(single-flight). 클래스 Javadoc 참고. */
    private final ReentrantLock issueLock = new ReentrantLock();

    public TokenManager(WebClient.Builder builder, KiwoomProperties properties) {
        this.properties = properties;
        this.webClient = builder.baseUrl(properties.restBaseUrl()).build();
    }

    /**
     * 유효한 접근토큰을 반환한다. 없거나 곧 만료되면 새로 발급받는다.
     * 호출자는 만료 걱정 없이 그냥 이 메서드만 부르면 된다.
     */
    public String accessToken() {
        CachedToken token = cached.get();
        if (token == null || token.expiresSoon()) {
            // 캐시가 없거나 곧 만료 — 락을 잡고 발급한다(single-flight). 락을 기다리는 동안
            // 다른 스레드가 이미 발급을 끝냈을 수 있으므로, 락 획득 직후 캐시를 다시 확인한다
            // (더블체크) — 그렇지 않으면 락이 풀릴 때마다 스레드 수만큼 중복 발급(429 원인)된다.
            issueLock.lock();
            try {
                token = cached.get();
                if (token == null || token.expiresSoon()) {
                    token = issue();
                    cached.set(token);
                }
            } finally {
                issueLock.unlock();
            }
        }
        return token.value();
    }

    /** 키움에 토큰 발급을 요청한다. 실패 시 예외 — 호출자(재시도 로직)가 처리. */
    private CachedToken issue() {
        log.info("접근토큰 발급 요청");
        Map<String, Object> response = callApi();
        if (response == null || response.get("token") == null) {
            throw new KiwoomApiException("토큰 발급 실패: 응답 없음");
        }
        // HTTP 200이어도 return_code != 0이면 논리 오류(키/시크릿 오류 등) — 실측 응답 포맷:
        // {"expires_dt":"20260814121649","return_msg":"...","token_type":"Bearer","return_code":0,"token":"..."}
        Object returnCode = response.get("return_code");
        if (returnCode != null && ((Number) returnCode).intValue() != 0) {
            throw new KiwoomApiException("토큰 발급 실패: " + response.get("return_msg"));
        }
        return new CachedToken((String) response.get("token"), parseExpiresAt(response));
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
            return Instant.now().plusSeconds(23 * 3600);
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
     */
    private record CachedToken(String value, Instant expiresAt) {

        /** 만료 5분 전부터 true — 선제 갱신 트리거. */
        boolean expiresSoon() {
            return Instant.now().plusSeconds(300).isAfter(expiresAt);
        }
    }
}

package com.autostock.kiwoom;

import com.autostock.common.event.BrokerAuthFailure;
import com.autostock.common.util.SecretMasking;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * 키움 REST 호출의 <b>단일 진입점</b> — 모든 REST 호출은 반드시 이 클래스를 거친다.
 *
 * <p>왜 한 곳으로 모으는가? 키움 API 호출에는 매번 붙어야 하는 공통 규칙이 있다:
 * <pre>
 *   호출 요청
 *     │
 *     ▼
 *   ① TR별 rate limit 통과 대기   ← {@link TrRateLimiter} (사전 예방)
 *     │
 *     ▼
 *   ② 429 응답 시 백오프 재시도    ← 그래도 초과했다면 (사후 대응, 최대 4회)
 *     │
 *     ▼
 *   ③ 토큰 첨부 + api-id 헤더     ← {@link TokenManager}, {@link TrId}
 *     │
 *     ▼
 *   키움 서버
 * </pre>
 * 이 규칙을 흩어놓으면 하나라도 빠뜨린 호출이 사고를 낸다.
 * (예: api-id 오타 → 엉뚱한 TR 실행, PLAN 3절 사고 사례)
 *
 * <p>참고: 키움 REST는 <b>조회도 POST 방식</b>이다. HTTP 메서드가 아니라
 * api-id 헤더가 "무엇을 할지"를 결정한다 — 그래서 TrId enum 강제가 중요하다.
 *
 * <p><b>오류 분류(Phase 0.6, {@link KiwoomErrorCodes}, aiDoc/kiwoom-error-codes.md)</b>: 유량 1700/1701/1702는
 * 1.1초 간격 재시도, 토큰 거절 8005/8010은 재발급 후 1회 재시도, 인증 실패 8001/8002/8010/8030/8031/8040/8050/8103은
 * 재시도 없이 {@link BrokerAuthFailure}를 발행한다(monitor가 텔레그램 긴급 알림). 응답 시간 초과는
 * {@link KiwoomTimeoutException}(결과 불명)으로 명시 오류와 구분한다.
 */
@Component
public class KiwoomRestClient {

    private static final Logger log = LoggerFactory.getLogger(KiwoomRestClient.class);

    /**
     * REST 1회 호출 상한. Reactor Netty 기본은 응답 타임아웃이 없어 PC 절전·망 단절 뒤 죽은 소켓에서
     * {@code block()}이 무한 대기할 수 있다(2026-09-22 절전 복귀 사고의 잠재 경로). 키움 조회 TR은
     * 보통 1초 안에 끝나므로 15초면 충분하고, 주문 TR은 이 시간 안에 응답이 없으면 어차피
     * Reconciliation(ka10075 대사)이 결과를 확정한다 — 타임아웃은 재시도하지 않는다(주문 중복 방지).
     */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final WebClient webClient;
    private final TokenManager tokenManager;
    private final TrRateLimiter rateLimiter;
    private final MeterRegistry meterRegistry;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    /** 알림에 싣는 서버 호스트 — mockapi(모의)·api(실전) 구분용. */
    private final String host;

    /**
     * 호출 한도 초과 전용 재시도 — HTTP 429, 그리고 HTTP 200으로 오는 논리 오류 return_code 5
     * {@code [1700:허용된 API 요청 개수를 초과하였습니다]}(실측 2026-09-23, ka10080/ka10081). 공식 스펙의
     * 1701(전체 총유량)·1702(그룹 유량)도 같은 성격이라 함께 재시도한다(Phase 0.6).
     * 모두 "서버가 요청을 처리하지 않고 거절"한 경우라 재전송해도 중복 사고가 없다.
     * 다른 오류는 재시도하지 않는다 — 주문 중복 위험 때문.
     */
    private final Retry retry;

    public KiwoomRestClient(WebClient.Builder builder,
                            KiwoomProperties properties,
                            TokenManager tokenManager,
                            TrRateLimiter rateLimiter,
                            MeterRegistry meterRegistry,
                            ApplicationEventPublisher publisher,
                            Clock clock) {
        this.webClient = builder.baseUrl(properties.restBaseUrl()).build();
        this.tokenManager = tokenManager;
        this.rateLimiter = rateLimiter;
        this.meterRegistry = meterRegistry;
        this.publisher = publisher;
        this.clock = clock;
        this.host = URI.create(properties.restBaseUrl()).getHost();
        this.retry = Retry.of("kiwoom-429", RetryConfig.custom()
                .maxAttempts(4)                          // 최초 1회 + 재시도 3회
                .waitDuration(Duration.ofMillis(1100))   // 재시도 간격 — 유량 1건/초 창을 확실히 넘긴다
                // 429만 재시도 대상: 타임아웃·5xx를 무턱대고 재시도하면
                // "주문이 실제로는 접수됐는데 또 보내는" 중복 사고가 날 수 있다
                .retryOnException(e -> e instanceof WebClientResponseException.TooManyRequests
                        || isRateLimitLogicError(e))
                .build());
    }

    /**
     * 키움 REST API를 호출한다.
     *
     * @param trId TR 식별자 — api-id 헤더로 전송되며, rate limit 버킷 키로도 쓰인다
     * @param path API 경로 (예: "/api/dostk/stkinfo")
     * @param body 요청 본문 (TR별 파라미터)
     * @return 응답 JSON을 Map으로 (타입 매핑은 각 서비스에서 TR별 DTO로 발전시킬 것)
     * @throws KiwoomApiException 4xx/5xx 오류 응답 시
     */
    public Map<String, Object> call(TrId trId, String path, Map<String, Object> body) {
        // kiwoom.api.latency: TR(api_id)별 지연 분포를 태그로 나눠 기록한다. rate limiter 대기
        // 시간까지 포함해서 재는데(의도적) — "얼마나 기다렸는지"가 rate limit 튜닝의 원천이고,
        // 체결까지 걸린 총 시간은 슬리피지 분석의 기초 데이터이기 때문이다 (PLAN ADR-5).
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            // 바깥: rate limiter (통과할 때까지 대기) → 토큰 거부 시 1회 재발급 → 429 재시도 → 최심부: 실제 HTTP 호출
            return rateLimiter.execute(trId, () -> callRefreshingRejectedToken(trId, path, body));
        } catch (KiwoomApiException e) {
            reportAuthFailure(trId, e);
            throw e;
        } finally {
            sample.stop(Timer.builder("kiwoom.api.latency")
                    .description("TR(api_id)별 키움 REST API 호출 지연(rate limit 대기 포함)")
                    .tag("api_id", trId.apiId())
                    .register(meterRegistry));
        }
    }

    /**
     * 재시도로 풀리지 않는 인증 실패면 {@link BrokerAuthFailure}를 발행한다 — 텔레그램 긴급 알림(monitor)으로 이어진다.
     * 토큰 발급 실패({@link KiwoomTokenIssueException})는 {@link TokenManager}가 이미 발행했으므로 건너뛴다.
     */
    private void reportAuthFailure(TrId trId, KiwoomApiException e) {
        if (e instanceof KiwoomTokenIssueException) {
            return;
        }
        KiwoomErrorCodes.find(e.getMessage(), KiwoomErrorCodes.AUTH_FAILURE).ifPresent(code -> {
            log.error("키움 인증 실패 [{}] api-id={} host={} — 재시도로 풀리지 않는 오류, 긴급 알림 발행", code, trId.apiId(), host);
            publisher.publishEvent(new BrokerAuthFailure(code, SecretMasking.mask(e.getMessage()), host, clock.instant()));
        });
    }

    /**
     * 토큰이 거부되면({@code [8005:Token이 유효하지 않습니다]}) 캐시를 버리고 새 토큰으로 딱 한 번 더 보낸다.
     * {@code [8010:Token을 발급받은 IP와 서비스를 요청한 IP가 동일하지 않습니다]}도 같다(Phase 0.6) — 공인 IP가 바뀌었으면
     * 새 IP에서 재발급한 토큰으로 풀린다(새 IP가 허용 IP 목록에 있어야 한다 — 없으면 재시도도 8010, 인증 실패 알림).
     * 서버가 인증 단계에서 거절해 요청을 처리하지 않았으므로 주문 TR이어도 중복 위험이 없다(1700 재시도와 같은 근거).
     * 실측 2026-09-30: 절전 복귀 직후 만료 전 토큰이 8005로 거부돼 리포트·분봉 적재가 전부 실패했다.
     */
    private Map<String, Object> callRefreshingRejectedToken(TrId trId, String path, Map<String, Object> body) {
        String token = tokenManager.accessToken();
        try {
            return callWithRateLimitRetry(trId, path, body, token);
        } catch (KiwoomApiException e) {
            if (!isTokenRejected(e)) {
                throw e;
            }
            tokenManager.invalidate(token);
            return callWithRateLimitRetry(trId, path, body, tokenManager.accessToken());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callWithRateLimitRetry(TrId trId, String path, Map<String, Object> body, String token) {
        return Retry.decorateSupplier(retry, () -> {
            Map<String, Object> response = (Map<String, Object>) webClient.post()
                    .uri(path)
                    .header("authorization", "Bearer " + token)
                    .header("api-id", trId.apiId())
                    .contentType(MediaType.valueOf("application/json;charset=UTF-8"))
                    .bodyValue(body)
                    .retrieve()
                    // 오류 응답이면 본문을 읽어 예외 메시지에 포함 — 디버깅 편의
                    .onStatus(HttpStatusCode::isError, resp ->
                            resp.bodyToMono(String.class).map(msg ->
                                    new KiwoomApiException("키움 API 오류 [" + trId.apiId() + "] " + msg)))
                    .bodyToMono(Map.class)
                    .timeout(REQUEST_TIMEOUT, Mono.error(() -> new KiwoomTimeoutException(
                            "키움 API 응답 없음 [" + trId.apiId() + "] " + REQUEST_TIMEOUT.toSeconds()
                                    + "초 내 응답 없음 — 망 단절/절전 복귀 여부 확인")))
                    .block();
            return checkReturnCode(trId, response);
        }).get();
    }

    /**
     * 키움이 접근토큰을 거부했는가 — 실측 메시지 {@code 인증에 실패했습니다[8005:Token이 유효하지 않습니다]},
     * 공식 스펙 {@code [8010:…IP와 서비스를 요청한 IP가 동일하지 않습니다]}.
     */
    static boolean isTokenRejected(Throwable e) {
        return KiwoomErrorCodes.has(e, KiwoomErrorCodes.TOKEN_REJECTED);
    }

    /** 키움이 돌려주는 유량 초과 논리 오류(return_code 5, 메시지 [1700:…]·[1701:…]·[1702:…])인가. */
    static boolean isRateLimitLogicError(Throwable e) {
        return KiwoomErrorCodes.has(e, KiwoomErrorCodes.RATE_LIMIT);
    }

    /**
     * HTTP 200이어도 키움 응답 본문의 {@code return_code}가 0이 아니면 논리 오류다
     * (예: 파라미터 오류, 권한 없음 등). 실측(kt00018/ka10081)상 정상 응답도 이 필드를
     * 포함하므로, 있으면 항상 검사한다 — 없는 TR도 있을 수 있어 필드 부재는 통과시킨다.
     */
    private Map<String, Object> checkReturnCode(TrId trId, Map<String, Object> response) {
        if (response == null) {
            return null;
        }
        Object returnCode = response.get("return_code");
        if (returnCode != null && ((Number) returnCode).intValue() != 0) {
            throw new KiwoomApiException(
                    "키움 API 논리 오류 [" + trId.apiId() + "] " + response.get("return_msg"));
        }
        return response;
    }
}

package com.autostock.risk;

import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Side;
import com.autostock.common.event.Signal;
import com.autostock.common.util.ClientOrderId;
import com.autostock.common.util.MarketConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 리스크 게이트 — <b>모든 주문이 반드시 통과해야 하는 유일한 관문</b>.
 *
 * <p>왜 필요한가? 전략(strategy)은 "사고 싶다/팔고 싶다"는 의견(Signal)만 낼 수 있고,
 * 실제 돈이 걸린 주문(OrderRequest)으로 바꾸는 권한은 이 클래스에만 있다.
 * 전략 코드에 버그가 있어도 여기서 걸러지므로 계좌가 보호된다. (PLAN 4절 불변 원칙)
 *
 * <p>검사 순서 — 순서가 중요하다:
 * <ol>
 *   <li><b>킬스위치</b>: 비상 정지 상태면 무조건 거부. 가장 싸고 빠른 검사라 맨 앞.</li>
 *   <li><b>사이징</b>: 몇 주를 살/팔 수 있는지 계산. 0주면 여기서 끝 —
 *       주문 슬롯(일 한도)을 낭비하지 않기 위해 한도 검사보다 먼저 한다.</li>
 *   <li><b>일 주문 한도</b>: 하루 주문 횟수 상한. 폭주(버그로 인한 연속 주문)의 마지막 방어선.</li>
 * </ol>
 *
 * <pre>
 *  Signal ──▶ [킬스위치?] ──▶ [사이징 &gt; 0?] ──▶ [일 한도 OK?] ──▶ OrderRequest 발행
 *                │ 거부            │ 거부              │ 거부
 *                ▼                ▼                  ▼
 *              (로그만 남기고 조용히 버린다 — 예외를 던지지 않는 이유:
 *               시그널 거부는 "정상 동작"이지 오류가 아니기 때문)
 * </pre>
 */
@Component
public class RiskGate {

    private static final Logger log = LoggerFactory.getLogger(RiskGate.class);

    private final ApplicationEventPublisher publisher;
    private final KillSwitch killSwitch;
    private final RiskProperties properties;
    private final PositionSizer sizer;
    private final PositionBook positionBook;
    private final DailyLimitTracker dailyLimits;

    public RiskGate(ApplicationEventPublisher publisher,
                    KillSwitch killSwitch,
                    RiskProperties properties,
                    PositionSizer sizer,
                    PositionBook positionBook,
                    DailyLimitTracker dailyLimits) {
        this.publisher = publisher;
        this.killSwitch = killSwitch;
        this.properties = properties;
        this.sizer = sizer;
        this.positionBook = positionBook;
        this.dailyLimits = dailyLimits;
    }

    /**
     * 전략이 발행한 {@link Signal}을 받아 검사 후, 통과 시에만 {@link OrderRequest}를 발행한다.
     *
     * @param signal 전략의 매매 의견 (이 시점에는 아직 아무 돈도 걸려있지 않다)
     */
    @EventListener
    public void onSignal(Signal signal) {
        // ── 1단계: 킬스위치 ─────────────────────────────────────────────
        // 텔레그램 명령·일 손실 한도·WS 단절 등 어떤 이유로든 비상 정지 상태면
        // 신규 주문은 전면 차단된다.
        if (killSwitch.isEngaged()) {
            log.warn("킬스위치 작동 중 — 시그널 거부: {}", signal.symbol());
            return;
        }

        // ── 2단계: 사이징 (몇 주?) ──────────────────────────────────────
        // 매수: 계좌의 일정 비율만 투입 / 매도: 보유 수량 전량 청산.
        // switch 식(expression)을 쓰면 Side에 새 값이 추가될 때 컴파일러가
        // 처리 누락을 잡아준다.
        long quantity = switch (signal.side()) {
            case BUY -> sizeBuy(signal);
            case SELL -> sizeSell(signal);
        };
        if (quantity <= 0) {
            return; // 거부 사유는 sizeBuy/sizeSell 안에서 이미 로그로 남겼다
        }

        // ── 3단계: 일 주문 한도 ─────────────────────────────────────────
        // 사이징까지 통과한 "진짜 주문 후보"만 슬롯을 소비한다.
        if (!dailyLimits.tryAcquireOrderSlot()) {
            log.warn("일 주문 한도 초과 — 거부: {}", signal.symbol());
            return;
        }

        // ── 통과: 주문 요청 발행 ────────────────────────────────────────
        // idempotencyKey(멱등키): 같은 주문이 두 번 실행되는 사고를 막는 고유 번호표.
        // PLAN.md ADR-6 7절 — UUID 대신 사람이 읽을 수 있는 ClientOrderId 포맷을 쓴다
        // ("20260813-BREAKOUT-005930-BUY-005"). OrderRequest.idempotencyKey 필드 자체는
        // 스키마 변경 없이 그대로 재사용한다 — 담기는 문자열의 "형식"만 바뀐 것이다.
        // 일련번호는 dailyLimits.todayOrderCount()를 그대로 쓴다 — 바로 위에서
        // tryAcquireOrderSlot()이 이미 카운터를 증가시켰으므로, 이 시점의 값이 곧
        // "이 주문이 오늘 몇 번째인지"와 같다(다시 증가시키지 않는다).
        String clientOrderId = ClientOrderId.generate(
                LocalDate.now(MarketConstants.KST),
                signal.strategyId(),
                signal.symbol(),
                signal.side(),
                dailyLimits.todayOrderCount()
        ).value();

        publisher.publishEvent(new OrderRequest(
                clientOrderId,
                signal.strategyId(),
                signal.symbol(),
                signal.side(),
                quantity,
                signal.refPrice(),
                Instant.now()));
    }

    /**
     * 매수 수량 결정. 다음 세 가지를 모두 만족해야 0보다 큰 수량이 나온다.
     * <ul>
     *   <li>미보유 종목일 것 — 물타기(추가 매수)는 의도적으로 금지</li>
     *   <li>동시 보유 종목 수가 한도 미만일 것 — 분산 한도 (기본 5종목)</li>
     *   <li>고정비율 사이징 결과가 1주 이상일 것</li>
     * </ul>
     *
     * <p><b>confidence = 투입 비중(2026-08-13)</b>: 예산 = equity × maxPositionPctPerSymbol ×
     * signal.confidence(). 스키마(Signal.confidence)는 원래 "확신도"라는 이름이지만, C3
     * 같은 사이징 전략(변동성 타게팅)은 이 필드에 "얼마나 투입할지"(0~1의 비중)를 실어
     * 보낸다 — 새 필드를 추가하지 않고 기존 confidence 필드를 재사용하는 설계 판단이다.
     * 기존 전략들처럼 confidence=1.0이면 이 곱셈이 결과에 영향을 주지 않는다(수치 무변화).
     */
    private long sizeBuy(Signal signal) {
        if (positionBook.holds(signal.symbol())) {
            log.info("이미 보유 중 — 추가 매수 차단: {}", signal.symbol());
            return 0;
        }
        if (positionBook.openPositionCount() >= properties.maxConcurrentPositions()) {
            log.info("동시 보유 한도 도달({}) — 매수 거부: {}",
                    properties.maxConcurrentPositions(), signal.symbol());
            return 0;
        }
        // TODO Phase 2 후반: LIVE 모드에서는 브로커 잔고 조회로 equity를 실시간 갱신한다.
        //  지금은 설정값(paper-equity)을 쓰므로 SIM/모의 전용.
        BigDecimal equity = BigDecimal.valueOf(properties.paperEquity());
        double confidence = clampConfidence(signal);
        long qty = sizer.sizeBuy(equity, signal.refPrice(), confidence);
        if (qty <= 0) {
            log.info("사이징 결과 0주 — 매수 불가: {} (equity={}, price={}, confidence={})",
                    signal.symbol(), equity, signal.refPrice(), confidence);
        }
        return qty;
    }

    /**
     * confidence는 전략이 어떤 값을 보내든(버그로 음수·0·1 초과가 와도) 사이징 계산이
     * 안전하게 끝나야 한다 — 범위(0, 1]를 벗어나면 경고 로그를 남기고 1.0(전액)으로
     * 클램프한다. "무조건 거부"가 아니라 "안전한 기본값으로 대체"를 택한 이유는, 사이징
     * 신호 하나 때문에 정상적인 매수 시그널 전체를 버리는 것이 더 위험하다고 판단해서다.
     */
    private double clampConfidence(Signal signal) {
        double confidence = signal.confidence();
        if (confidence <= 0.0 || confidence > 1.0) {
            log.warn("confidence 범위(0,1] 벗어남({}) — 1.0으로 클램프: {}", confidence, signal.symbol());
            return 1.0;
        }
        return confidence;
    }

    /**
     * 매도 수량 결정 — 정책: <b>부분 매도 없이 전량 청산</b>.
     * 보유하지 않은 종목의 매도 시그널은 무시한다(공매도 미지원).
     */
    private long sizeSell(Signal signal) {
        PositionBook.Position position = positionBook.get(signal.symbol());
        if (position == null) {
            log.info("미보유 종목 매도 시그널 무시: {}", signal.symbol());
            return 0;
        }
        return position.quantity();
    }
}

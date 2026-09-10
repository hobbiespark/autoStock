package com.autostock.strategy;

import com.autostock.common.event.Candle;
import com.autostock.common.event.Side;
import com.autostock.common.event.Signal;
import com.autostock.common.event.SignalDecision;
import com.autostock.common.util.MarketConstants;
import com.autostock.market.KiwoomDailyChartService;
import com.autostock.market.MarketCalendarService;
import com.autostock.monitor.TradingSystemManager;
import com.autostock.monitor.TradingSystemStatus;
import com.autostock.portfolio.PositionBook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * C3 라이브 전략 — 시계열 모멘텀 + KODEX200 SMA200 국면필터 + 변동성 타게팅을
 * 실제 매매 이벤트 루프에 탑재한 Imperative Shell.
 *
 * <h2>왜 스케줄 기반인가(MarketTick 리스너가 아니라)</h2>
 * 이 전략이 쓰는 판단(모멘텀·국면·변동성)은 전부 "일봉" 단위이고, 판단 주기도
 * {@link C3StrategyProperties#decisionIntervalDays}일(기본 21영업일)에 한 번뿐이다.
 * 장중 틱을 볼 이유가 전혀 없으므로, {@code StrategyEngine}의 {@code MarketTick} 이벤트
 * 리스너 경로를 쓰지 않고 하루 한 번(정규장 시작 직후 09:05 KST) 스스로 판단을 내린다.
 *
 * <h2>순수 함수 vs Imperative Shell</h2>
 * 매매 판단 계산 자체({@link MomentumMath}, {@link RegimeMath}, {@link VolTargetMath})는
 * 모두 순수 함수이고, 백테스트({@code backtest} 모듈)와 완전히 동일한 로직을 공유한다
 * (ARCHITECTURE.md 규칙 17·18). 이 클래스는 그 순수 함수들에 넣을 입력을 얻기 위한
 * I/O(일봉 조회, 포지션 조회, 이벤트 발행)만 담당한다 — "Imperative Shell, Functional Core"
 * 구조다. 이 클래스 안에는 매매 판단식이 단 한 줄도 새로 계산되지 않는다.
 *
 * <h2>실행 흐름</h2>
 * <ol>
 *   <li>{@link C3StrategyProperties#regimeIndexSymbol} 일봉을 조회해 {@link RegimeMath}로
 *       국면(ON/OFF)을 판정한다.</li>
 *   <li>OFF면: 이 전략이 관리하는 종목 중 보유 중인 것 전부 SELL Signal을 발행하고
 *       종료한다(신규 진입은 아예 판단하지 않는다).</li>
 *   <li>ON이면: 종목별로 "판단일"(마지막 판단 후 decisionIntervalDays가 지났는지)인지
 *       확인하고, 판단일이면 일봉을 조회해 {@link MomentumMath}로 상승/하락 추세를 가른다.
 *       상승 전환+미보유면 {@link VolTargetMath}로 투입 비중을 계산해 BUY, 하락 전환+보유면
 *       SELL을 발행한다.</li>
 * </ol>
 *
 * <h2>confidence = 투입 비중</h2>
 * BUY Signal의 {@code confidence} 필드에는 {@link VolTargetMath#fraction}이 계산한 값을
 * 그대로 싣는다(0 초과 1 이하) — Signal 스키마를 바꾸지 않고 "얼마나 살지"를 전달하는
 * 방법이다. {@code risk.RiskGate}가 이 값을 사이징(equity × 종목당한도 × confidence)에
 * 곱한다({@code PositionSizer.sizeBuy} 참고).
 *
 * <h2>21일 주기 카운터 — 인메모리, 영속화 TODO</h2>
 * {@link #lastDecisionDate}는 종목별 "마지막 판단일"을 인메모리 맵으로만 들고 있다.
 * 앱을 재시작하면 이 상태가 사라져, 재시작 직후 스케줄에서는 모든 종목을 다시 판단하게
 * 된다(원래 예정보다 이를 수 있음). TODO: DB 테이블로 영속화해 재시작 후에도 원래
 * 판단 주기를 유지하도록 개선해야 한다 — 지금은 "판단을 건너뛰기보다 한 번 더 하는 쪽이
 * 안전하다"는 보수적 방향으로 남겨둔다.
 *
 * <h2>예외 격리</h2>
 * 종목 하나의 조회·계산 실패(네트워크 오류, 데이터 부족 등)가 나머지 종목 판단을 막지
 * 않도록, 종목별 판단은 개별적으로 예외를 잡아 error 로그만 남기고 다음 종목으로 넘어간다.
 *
 * <h2>운영 상태기계와의 이중 가드</h2>
 * {@code strategy.c3.enabled} 플래그와 별개로, {@link TradingSystemManager#status()}가
 * {@link TradingSystemStatus#RUNNING}이 아니면 이번 스케줄을 통째로 스킵한다(ARCHITECTURE.md
 * 10절 — 시스템이 STARTING/STOPPING/DEGRADED/ERROR면 매매하면 안 된다). 이 조회는 monitor
 * 모듈의 공개 API(TradingSystemManager, 루트 패키지) 직접 호출이다 — "조회는 인터페이스
 * 직접 호출 허용"(ARCHITECTURE.md 9절)에 따른 것이며, monitor는 strategy를 참조하지 않으므로
 * (반대 방향 의존 없음) 모듈 순환이 생기지 않는다(ModularityTests로 확인됨).
 */
@Component
public class C3LiveStrategy {

    private static final Logger log = LoggerFactory.getLogger(C3LiveStrategy.class);

    /** 이 전략이 발행하는 모든 Signal의 strategyId — RiskGate 로그·ClientOrderId에 그대로 노출된다. */
    private static final String STRATEGY_ID = "C3-MOMENTUM";

    /** 이 전략의 보유기간 지평(ADR-11) — 중기. FE-6 SignalDecision.horizon에 그대로 실린다. */
    private static final String HORIZON = "MID";

    private final C3StrategyProperties properties;
    private final KiwoomDailyChartService chartService;
    private final PositionBook positionBook;
    private final ApplicationEventPublisher publisher;
    private final MarketCalendarService marketCalendarService;
    private final TradingSystemManager tradingSystemManager;

    /**
     * 종목별 "마지막 판단일" — decisionIntervalDays 주기 카운터의 인메모리 상태.
     * 클래스 설명 "21일 주기 카운터" 절 참고(영속화 TODO).
     */
    private final Map<String, LocalDate> lastDecisionDate = new ConcurrentHashMap<>();

    public C3LiveStrategy(C3StrategyProperties properties,
                          KiwoomDailyChartService chartService,
                          PositionBook positionBook,
                          ApplicationEventPublisher publisher,
                          MarketCalendarService marketCalendarService,
                          TradingSystemManager tradingSystemManager) {
        this.properties = properties;
        this.chartService = chartService;
        this.positionBook = positionBook;
        this.publisher = publisher;
        this.marketCalendarService = marketCalendarService;
        this.tradingSystemManager = tradingSystemManager;
    }

    /** 매 평일 09:05 KST(정규장 09:00 개장 직후) 1회 실행 — 클래스 설명 "왜 스케줄 기반인가" 참고. */
    @Scheduled(cron = "0 5 9 * * MON-FRI", zone = "Asia/Seoul")
    public void run() {
        if (!properties.enabled()) {
            return; // 자택망 검증 전 기본 비활성 — C3StrategyProperties Javadoc 참고
        }
        TradingSystemStatus systemStatus = tradingSystemManager.status();
        if (systemStatus != TradingSystemStatus.RUNNING) {
            // 이중 가드 — enabled=true여도 운영 상태기계가 RUNNING이 아니면 매매하지 않는다
            // (클래스 설명 "운영 상태기계와의 이중 가드" 참고).
            log.info("C3: 운영 상태가 RUNNING이 아님({}) — 이번 스케줄 스킵", systemStatus);
            return;
        }

        LocalDate today = LocalDate.now(MarketConstants.KST);
        if (!marketCalendarService.isTradingDay(today)) {
            // cron은 MON-FRI만 걸지만 평일 중 공휴일(신정·설·추석 등)은 별도로 걸러야 한다 —
            // 휴장일에 일봉을 조회하면 "어제 종가"가 아니라 더 예전 데이터를 오늘 것으로
            // 착각해 판단이 틀어질 수 있다. 판정은 market 모듈의 MarketCalendarService에
            // 위임한다 — DB에 동기화된 특일 데이터가 있으면 그것을, 없으면 TradingCalendar
            // 하드코딩으로 폴백한다(MarketCalendarService 클래스 설명 참고).
            log.info("C3: 휴장일({}) — 이번 스케줄 스킵", today);
            return;
        }

        RegimeSnapshot regime = judgeRegime();
        if (!regime.on()) {
            // OFF — 신규 진입을 아예 판단하지 않고, 보유 중인 것만 강제 청산한다.
            // FE-6(판단 근거) — 이 스케줄에서 평가 대상이었던 모든 종목에 대해 "왜 안 샀나"를
            // 재구성할 수 있도록 종목별 SKIP 판단 근거를 남긴다(RiskGate 거부와 달리, 여기는
            // Signal 자체를 아예 내지 않았으므로 strategy 쪽에서 직접 발행해야 한다).
            for (String symbol : properties.symbols()) {
                publishDecision(symbol, "SKIP", "국면 OFF(지수 종가가 SMA200 이하) — 신규 진입 판단 보류",
                        regimeMetrics(regime));
            }
            liquidateAll();
            return;
        }

        for (String symbol : properties.symbols()) {
            try {
                decideOne(symbol, today, regime);
            } catch (RuntimeException e) {
                // 종목 단위 격리 — 한 종목의 실패가 전체 배치를 중단시키지 않는다(클래스 설명 참고).
                log.error("C3: 종목 {} 판단 중 오류 — 이 종목만 스킵하고 계속 진행", symbol, e);
            }
        }
    }

    /**
     * 국면 판정 — 지수의 최근 (regimeSmaDays+1)봉 이상을 조회해 {@link RegimeMath#isOn}에
     * 그대로 넘긴다. 09:05(개장 직후) 호출이므로 조회 결과의 마지막 봉은 항상 "어제 종가"다
     * (오늘 일봉은 장중에야 만들어지므로 아직 존재하지 않는다) — RegimeMath가 기대하는
     * "전일까지" 계약과 자연스럽게 맞아떨어진다.
     */
    private RegimeSnapshot judgeRegime() {
        List<Candle> candles = chartService.fetchDaily(
                properties.regimeIndexSymbol(), LocalDate.now(MarketConstants.KST), properties.regimeSmaDays() + 1);
        List<BigDecimal> closes = candles.stream().map(Candle::close).toList();
        boolean on = RegimeMath.isOn(closes);
        // FE-6 판단 근거용 표시값 — RegimeMath.isOn()과 같은 입력(closes)을 받아 같은
        // "전일 종가 vs SMA(regimeSmaDays)" 계산을 이 shell에서 한 번 더 구해 보여준다.
        // 실제 매매 결론(on)은 위 RegimeMath.isOn() 호출 결과 그대로이고, 이 값들은 화면에
        // "왜 ON/OFF인지"를 보여주기 위한 부가 표시값일 뿐 판단식 자체에는 영향을 주지 않는다
        // (RegimeMath는 절대 수정하지 않는다 — 클래스 설명 "순수 함수 vs Imperative Shell" 참고).
        BigDecimal indexClose = closes.isEmpty() ? null : closes.get(closes.size() - 1);
        BigDecimal sma = displaySma(closes, properties.regimeSmaDays());
        return new RegimeSnapshot(on, properties.regimeIndexSymbol(), indexClose, sma);
    }

    /** 화면 표시 전용 SMA — RegimeMath.SMA_WINDOW(200) 고정이 아니라 설정된 regimeSmaDays로 구한다. */
    private static BigDecimal displaySma(List<BigDecimal> closes, int windowDays) {
        if (closes == null || closes.size() < windowDays || windowDays <= 0) {
            return null;
        }
        int n = closes.size();
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = n - windowDays; i < n; i++) {
            sum = sum.add(closes.get(i));
        }
        return sum.divide(BigDecimal.valueOf(windowDays), 4, RoundingMode.HALF_UP);
    }

    private static Map<String, String> regimeMetrics(RegimeSnapshot regime) {
        Map<String, String> metrics = new LinkedHashMap<>();
        metrics.put("regimeStatus", regime.on() ? "ON" : "OFF");
        metrics.put("regimeIndexSymbol", regime.indexSymbol());
        metrics.put("regimeIndexClose", regime.indexClose() == null ? "N/A" : regime.indexClose().toPlainString());
        metrics.put("regimeSma", regime.sma() == null ? "N/A" : regime.sma().toPlainString());
        return metrics;
    }

    /** 국면 판정 결과 + 화면 표시용 부가값(FE-6). 매매 결론은 {@link #on()}만 쓴다. */
    private record RegimeSnapshot(boolean on, String indexSymbol, BigDecimal indexClose, BigDecimal sma) {
    }

    /** 국면 OFF — 이 전략이 관리하는 종목 중 보유 중인 것 전부 SELL Signal 발행. */
    private void liquidateAll() {
        for (String symbol : properties.symbols()) {
            PositionBook.Position position = positionBook.get(symbol);
            if (position != null) {
                log.info("C3: 국면 OFF — 강제 청산: {}", symbol);
                publishSell(symbol, position.avgPrice());
            }
        }
    }

    /** 종목 하나의 판단 — 판단일이면 모멘텀/변동성을 계산해 BUY 또는 SELL을, 아니면 아무 것도 하지 않는다. */
    private void decideOne(String symbol, LocalDate today, RegimeSnapshot regime) {
        if (!isJudgmentDay(symbol, today)) {
            return; // decisionIntervalDays가 아직 안 지남 — 보유/미보유 상태를 그대로 유지.
            // FE-6 참고: 판단 주기가 아직 안 지난 종목은 이번 스케줄에서 "평가되지 않았다"고
            // 보고 SignalDecision을 남기지 않는다(재구성할 판단 자체가 없음) — 평가된 종목만
            // "왜 샀는지/안 샀는지" 기록 대상이다.
        }

        // MomentumMath는 최소 (lookbackN+1)개, VolTargetMath는 최소 (VOL_WINDOW+1)개가 필요하다.
        int minCount = Math.max(properties.lookbackN(), VolTargetMath.VOL_WINDOW) + 1;
        List<Candle> candles = chartService.fetchDaily(symbol, today, minCount);
        if (candles.size() < properties.lookbackN() + 1) {
            // 데이터가 부족하면 이번엔 판단을 건너뛴다 — lastDecisionDate를 갱신하지 않으므로
            // 다음 스케줄에서 즉시 재시도한다(21일을 더 기다리지 않는다).
            log.warn("C3: 종목 {} 캔들 부족({}개, 최소 {}개 필요) — 이번 판단 스킵",
                    symbol, candles.size(), properties.lookbackN() + 1);
            publishDecision(symbol, "SKIP",
                    "캔들 부족(" + candles.size() + "개, 최소 " + (properties.lookbackN() + 1) + "개 필요) — 이번 판단 스킵",
                    regimeMetrics(regime));
            return;
        }
        List<BigDecimal> closes = candles.stream().map(Candle::close).toList();

        // 조회에 성공해 실제로 판단을 내렸을 때만 "판단일"을 갱신한다.
        lastDecisionDate.put(symbol, today);

        boolean uptrend = MomentumMath.shouldHold(closes, properties.lookbackN());
        boolean holding = positionBook.holds(symbol);
        BigDecimal latestClose = candles.get(candles.size() - 1).close();

        // FE-6 판단 근거 지표 — MomentumMath와 같은 입력(closes, lookbackN)으로 "N일 수익률"을
        // 화면 표시용으로 계산한다(판단식 자체는 위 MomentumMath.shouldHold 호출 결과 그대로).
        Map<String, String> metrics = new LinkedHashMap<>(regimeMetrics(regime));
        metrics.put("momentumLookbackN", String.valueOf(properties.lookbackN()));
        metrics.put("momentumReturnPct", momentumReturnPct(closes, properties.lookbackN()));

        if (uptrend && !holding) {
            double fraction = VolTargetMath.fraction(closes, properties.targetVolAnnual());
            metrics.put("volTargetFraction", String.valueOf(fraction));
            metrics.put("targetVolAnnual", String.valueOf(properties.targetVolAnnual()));
            publishBuy(symbol, latestClose, fraction);
            publishDecision(symbol, "BUY", "모멘텀 상승 전환 + 국면 ON + 미보유 — 매수 시그널 발행", metrics);
        } else if (!uptrend && holding) {
            publishSell(symbol, latestClose);
            publishDecision(symbol, "SELL", "모멘텀 하락 전환 — 보유분 매도 시그널 발행", metrics);
        } else if (uptrend && holding) {
            publishDecision(symbol, "HOLD", "모멘텀 상승 유지 + 이미 보유 중 — 재진입 불필요, 그대로 유지", metrics);
        } else {
            publishDecision(symbol, "SKIP", "모멘텀 하락 추세 — 신규 진입하지 않고 미보유 유지", metrics);
        }
        // uptrend && holding → 그대로 보유 유지(재진입 불필요)
        // !uptrend && !holding → 그대로 미보유 유지(청산할 것이 없음)
    }

    /**
     * 화면 표시용 "N일 수익률" — (어제 종가 / N봉 전 종가 − 1) × 100, 백분율 문자열.
     * MomentumMath.shouldHold와 동일한 두 값(어제 종가, N봉 전 종가)을 쓰지만, 부등호 판정이
     * 아니라 수치 자체를 보여주기 위해 이 shell에서 별도로 계산한다(MomentumMath는 boolean만
     * 반환하고 절대 수정하지 않는다).
     */
    private static String momentumReturnPct(List<BigDecimal> closes, int lookbackN) {
        BigDecimal yesterday = closes.get(closes.size() - 1);
        BigDecimal nBarsAgo = closes.get(closes.size() - 1 - lookbackN);
        if (nBarsAgo.signum() == 0) {
            return "N/A";
        }
        BigDecimal pct = yesterday.subtract(nBarsAgo)
                .divide(nBarsAgo, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        return pct.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** SignalDecision 발행 — RegimeSnapshot이 없는(=국면 판단과 무관한) 호출 편의 오버로드는 두지 않는다: 항상 국면 지표를 함께 남긴다(FE-6, "왜"를 재구성하려면 국면도 필요). */
    private void publishDecision(String symbol, String conclusion, String reason, Map<String, String> metrics) {
        publisher.publishEvent(new SignalDecision(
                HORIZON, STRATEGY_ID, symbol, conclusion, reason, metrics, Instant.now()));
    }

    /** decisionIntervalDays 주기 판정 — 마지막 판단일이 없거나(첫 판단) 주기가 지났으면 true. */
    private boolean isJudgmentDay(String symbol, LocalDate today) {
        LocalDate last = lastDecisionDate.get(symbol);
        return last == null || !last.plusDays(properties.decisionIntervalDays()).isAfter(today);
    }

    /**
     * 매수 시그널 발행. confidence 필드에는 이 전략에서 투입 비중을 의미하는 fraction을
     * 싣는다(0 초과 1 이하) — 클래스 설명 "confidence = 투입 비중" 절 참고. 스키마 변경
     * 없이 그대로 전달하며, RiskGate가 사이징에 곱한다.
     */
    private void publishBuy(String symbol, BigDecimal refPrice, double fraction) {
        publisher.publishEvent(new Signal(STRATEGY_ID, symbol, Side.BUY, refPrice, fraction, Instant.now()));
    }

    /** 매도는 항상 전량 청산(RiskGate 정책)이라 confidence는 의미가 없다 — 1.0 고정. */
    private void publishSell(String symbol, BigDecimal refPrice) {
        publisher.publishEvent(new Signal(STRATEGY_ID, symbol, Side.SELL, refPrice, 1.0, Instant.now()));
    }
}

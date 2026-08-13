package com.autostock.backtest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 공개 일봉(야후 파이낸스, {@code scripts/fetch_yahoo_daily.py}로 수집) 데이터에 대해
 * 변동성 돌파 전략의 walk-forward 성과를 실측하는 검증 테스트.
 *
 * <h2>이 테스트가 "실험실 밖" 검증인 이유</h2>
 * 다른 백테스트 테스트들({@link WalkForwardRunnerTest} 등)은 시드 고정 합성 데이터로
 * 로직 자체(창 분할, 파라미터 선택, DSR 연동)가 규약대로 동작하는지만 확인한다. 이
 * 테스트는 반대로 로직은 이미 옳다고 가정하고, "그 로직을 진짜 시장 데이터에 돌리면
 * 어떤 숫자가 나오는가"를 확인한다 — PLAN이 요구하는 게이트①(OOS 수익 > 0, DSR > 0.95,
 * MDD < 15%) 통과 여부를 실데이터 기준으로 판단하기 위한 근거 자료다.
 *
 * <h2>데이터가 없으면 조용히 스킵한다(CI 안전)</h2>
 * data/ 디렉터리는 의도적으로 git에 커밋하지 않는다(라이선스·용량, .gitignore 참고).
 * 그래서 이 테스트는 데이터 파일을 찾지 못하면 실패가 아니라 {@code assumeTrue}로
 * 스킵한다 — CI나 데이터를 내려받지 않은 개발자 환경에서 이 테스트 때문에 빌드가
 * 깨지면 안 되기 때문이다. 로컬에서 실제로 돌려보려면 먼저
 * {@code python3 scripts/fetch_yahoo_daily.py} 를 실행해 data/ 를 채워야 한다.
 *
 * <h2>성과 수치는 assert하지 않는다</h2>
 * 실데이터는 스크립트를 다시 돌릴 때마다(오늘 날짜가 바뀌면) 범위가 달라지므로,
 * "몇 % 이상이어야 한다" 같은 assert는 곧바로 깨지는 테스트가 된다. 그래서 이 테스트는
 * "결과가 정상적으로 산출됐는가"(NaN 아님, 시계열 길이 > 0)만 assert하고, 실제 성과
 * 숫자와 게이트 판정은 System.out 표로 출력해 사람이 읽고 판단하게 한다.
 */
class RealDataWalkForwardTest {

    /** 검증 대상 5종목 — 종목코드(파일명, .KS 등 접미사 제외) → 표시용 종목명. */
    private static final Map<String, String> SYMBOLS = new LinkedHashMap<>();
    static {
        SYMBOLS.put("005930", "삼성전자");
        SYMBOLS.put("000660", "SK하이닉스");
        SYMBOLS.put("035420", "NAVER");
        SYMBOLS.put("035720", "카카오");
        SYMBOLS.put("069500", "KODEX200");
    }

    private static final List<Double> K_CANDIDATES = List.of(0.3, 0.4, 0.5, 0.6, 0.7);
    private static final int TRAIN_SIZE = 252;
    private static final int TEST_SIZE = 63;
    private static final BigDecimal INITIAL_CAPITAL = new BigDecimal("10000000");

    /** 게이트① 임계값 — PLAN에 명시된 최소 통과 기준. */
    private static final double GATE_MIN_DSR = 0.95;
    private static final double GATE_MAX_MDD = 0.15;

    private final CandleCsvLoader loader = new CandleCsvLoader();
    private final WalkForwardRunner runner = new WalkForwardRunner(CostModel.defaults());

    @Test
    void 공개_일봉_데이터로_5종목_walk_forward_실검증() {
        Path dataDir = resolveDataDir();
        assumeTrue(dataDir != null,
                "data/ 디렉터리를 찾지 못해 실데이터 검증을 스킵함 — "
                        + "먼저 `python3 scripts/fetch_yahoo_daily.py` 로 data/ 를 채운 뒤 다시 실행할 것");

        StringBuilder table = new StringBuilder();
        table.append(String.format(
                "%-10s | %10s | %10s | %8s | %8s | %8s | %6s | %6s | %s%n",
                "종목", "OOS총수익률", "CAGR", "MDD", "Sharpe", "DSR", "거래수", "trial수", "구간별 선택 k"));
        table.append("-".repeat(140)).append(System.lineSeparator());

        StringBuilder gateSummary = new StringBuilder();
        int verifiedSymbols = 0;

        for (Map.Entry<String, String> entry : SYMBOLS.entrySet()) {
            String code = entry.getKey();
            String name = entry.getValue();
            Path csv = dataDir.resolve(code + ".csv");
            if (!Files.isRegularFile(csv)) {
                System.out.println("[skip] " + code + "(" + name + ") CSV 없음: " + csv);
                continue;
            }

            List<Candle> candles = loader.load(csv);
            if (candles.size() < TRAIN_SIZE + TEST_SIZE) {
                System.out.println("[skip] " + code + "(" + name + ") 캔들 수가 부족함(최소 "
                        + (TRAIN_SIZE + TEST_SIZE) + "개 필요, 실제 " + candles.size() + "개)");
                continue;
            }

            WalkForwardResult result = runner.run(candles, K_CANDIDATES, TRAIN_SIZE, TEST_SIZE, INITIAL_CAPITAL);
            BacktestResult oos = result.oosResult();

            // ── 결과가 산출됐는지만 확인한다(성과 수치 자체는 assert하지 않음 — 클래스 설명 참고) ──
            assertFalse(oos.dailyReturns().isEmpty(), code + ": OOS 일별 수익률 시계열이 비어 있음");
            assertTrue(oos.dailyReturns().size() > 0, code + ": OOS 시계열 길이 > 0 이어야 함");
            assertFalse(Double.isNaN(oos.totalReturn()), code + ": totalReturn이 NaN");
            assertFalse(Double.isNaN(oos.cagr()), code + ": cagr이 NaN");
            assertFalse(Double.isNaN(oos.mdd()), code + ": mdd가 NaN");
            assertFalse(Double.isNaN(oos.sharpe()), code + ": sharpe가 NaN");
            assertFalse(Double.isNaN(oos.dsrConfidence()), code + ": dsrConfidence가 NaN");
            assertTrue(result.trials() > 0, code + ": trial 수는 0보다 커야 함");
            assertTrue(result.selectedParams().size() > 0, code + ": 구간별 선택 k 기록이 있어야 함");

            int windowCount = windowCount(candles.size(), TRAIN_SIZE, TEST_SIZE);
            double buyHoldReturn = buyHoldReturn(candles, TRAIN_SIZE, windowCount, TEST_SIZE);

            table.append(String.format(
                    "%-10s | %9.2f%% | %9.2f%% | %7.2f%% | %8.3f | %8.3f | %6d | %6d | %s%n",
                    code + "(" + name + ")",
                    oos.totalReturn() * 100,
                    oos.cagr() * 100,
                    oos.mdd() * 100,
                    oos.sharpe(),
                    oos.dsrConfidence(),
                    oos.tradeCount(),
                    result.trials(),
                    result.selectedParams()));
            table.append(String.format(
                    "%-10s   (참고) 단순보유(buy&hold, 동일 OOS 기간) 총수익률: %9.2f%%%n",
                    "", buyHoldReturn * 100));

            boolean gatePass = oos.totalReturn() > 0
                    && oos.dsrConfidence() > GATE_MIN_DSR
                    && oos.mdd() < GATE_MAX_MDD;
            gateSummary.append(String.format(
                    "  %-10s 게이트① = %s  (OOS수익>0: %s, DSR>0.95: %s(%.3f), MDD<15%%: %s(%.2f%%))%n",
                    code + "(" + name + ")",
                    gatePass ? "PASS" : "FAIL",
                    oos.totalReturn() > 0 ? "O" : "X",
                    oos.dsrConfidence() > GATE_MIN_DSR ? "O" : "X", oos.dsrConfidence(),
                    oos.mdd() < GATE_MAX_MDD ? "O" : "X", oos.mdd() * 100));

            verifiedSymbols++;
        }

        assumeTrue(verifiedSymbols > 0,
                "data/ 디렉터리는 있으나 유효한 종목 CSV가 하나도 없어 실데이터 검증을 스킵함");

        System.out.println();
        System.out.println("=== 변동성 돌파 전략 walk-forward 실데이터 검증 결과 ===");
        System.out.println("(trainSize=" + TRAIN_SIZE + ", testSize=" + TEST_SIZE
                + ", 초기자본=" + INITIAL_CAPITAL + ", 손절=-3%, k후보=" + K_CANDIDATES + ")");
        System.out.println();
        System.out.print(table);
        System.out.println();
        System.out.println("=== 게이트① 판정 (OOS 수익 > 0 AND DSR > 0.95 AND MDD < 15%) ===");
        System.out.print(gateSummary);
    }

    /**
     * data/ 디렉터리 경로를 찾는다. 우선순위:
     * 1) 시스템 프로퍼티 {@code autostock.data.dir}가 지정돼 있으면 그 값만 사용한다
     *    (명시적으로 지정했는데 없으면 스킵 사유를 명확히 하기 위해 다른 후보로 넘어가지 않는다).
     * 2) 지정이 없으면 "../data"(모듈 디렉터리 기준 상위, 즉 프로젝트 루트의 data/)를 시도한다.
     * 3) 그다음 "data"(현재 작업 디렉터리 기준)를 시도한다.
     * 셋 다 디렉터리로 존재하지 않으면 null을 반환한다(호출자가 assumeTrue로 스킵).
     */
    private Path resolveDataDir() {
        String override = System.getProperty("autostock.data.dir");
        if (override != null) {
            Path path = Paths.get(override);
            return Files.isDirectory(path) ? path : null;
        }

        for (String candidate : List.of("../data", "data")) {
            Path path = Paths.get(candidate);
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        return null;
    }

    /** WalkForwardRunner와 동일한 창 개수 계산 규칙(WalkForwardRunnerTest의 expectedWindowCount와 동일). */
    private int windowCount(int totalCandles, int trainSize, int testSize) {
        int count = 0;
        int start = 0;
        while (start + trainSize + testSize <= totalCandles) {
            count++;
            start += testSize;
        }
        return count;
    }

    /**
     * OOS 구간과 정확히 같은 기간(train 구간들 사이를 건너뛰지 않고 이어붙인 test 구간 전체,
     * 즉 candles[trainSize .. trainSize + windowCount*testSize))에 대해 "그날 시가에 사서
     * 마지막 날 종가에 파는" 단순 보유 수익률을 계산한다. walk-forward의 OOS 성과와 같은
     * 기간을 비교해야 전략이 그 구간의 "그냥 오르는 시장" 덕을 본 것인지 아닌지 알 수 있다.
     */
    private double buyHoldReturn(List<Candle> candles, int trainSize, int windowCount, int testSize) {
        int oosStart = trainSize;
        int oosEndExclusive = trainSize + windowCount * testSize;
        if (oosEndExclusive <= oosStart || oosEndExclusive > candles.size()) {
            return 0.0;
        }
        BigDecimal entryPrice = candles.get(oosStart).open();
        BigDecimal exitPrice = candles.get(oosEndExclusive - 1).close();
        if (entryPrice.signum() == 0) {
            return 0.0;
        }
        return exitPrice.subtract(entryPrice).divide(entryPrice, 12, RoundingMode.HALF_UP).doubleValue();
    }
}

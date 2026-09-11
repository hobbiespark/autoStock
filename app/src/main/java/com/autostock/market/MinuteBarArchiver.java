package com.autostock.market;

import com.autostock.common.util.KiwoomNumbers;
import com.autostock.common.util.MarketConstants;
import com.autostock.kiwoom.KiwoomRestClient;
import com.autostock.kiwoom.TrId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 분봉 일일 적재 잡 (2026-09-11 신설 — ka10080 깊이 실측의 직접 후속).
 *
 * <p><b>왜 필요한가</b>: ka10080은 <b>최근 1년 롤링 창</b>만 제공함이 실측으로 확정됐다
 * (005930 1분봉 95,930행, 2025-09-01까지 — PROGRESS §2). 즉 다년치 분봉 백테스트(단타
 * 지평의 게이트 v2 검증)는 API 조회만으로는 영원히 불가능하고, 지금부터 매일 적재해
 * 축적하는 것만이 유일한 경로다. 이 잡은 그 축적을 담당한다 — 하루라도 빨리 켤수록
 * 데이터 자산이 길어진다.
 *
 * <p><b>동작</b>: 평일 15:45 KST(장 마감 후, 15:50 일일 리포트 전)에 심볼별로 ka10080
 * 1페이지(900행 ≈ 최근 2.3거래일)를 받아 <b>오늘 날짜 행만</b> 골라 CSV에 append 한다.
 * 파일의 마지막 시각 이하 행은 건너뛰므로 재실행·중복 실행에 멱등이다. 휴장일은 오늘
 * 행이 0건이라 자연히 아무것도 쓰지 않는다.
 *
 * <p><b>실측 확정 필드 (2026-09-11, scripts/probe_ka10080_depth.ps1)</b>: 배열 키
 * {@code stk_min_pole_chart_qry}, 원소 {@code cntr_tm}(yyyyMMddHHmmss) /
 * {@code open_pric, high_pric, low_pric, cur_prc}(분 종가, ± 전일대비 부호 접두 가능
 * — {@link KiwoomNumbers}로 정규화) / {@code trde_qty}(분 거래량) /
 * {@code acc_trde_qty}(누적 거래량).
 *
 * <p>출력: {@code data/minutes/{symbol}.csv} — 헤더
 * {@code time,open,high,low,close,volume,acc_volume}. data/는 gitignore.
 */
@Component
public class MinuteBarArchiver {

    private static final Logger log = LoggerFactory.getLogger(MinuteBarArchiver.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE; // yyyyMMdd
    private static final String HEADER = "time,open,high,low,close,volume,acc_volume";

    private final KiwoomRestClient client;
    private final boolean enabled;
    private final List<String> symbols;
    private final Path outDir;

    public MinuteBarArchiver(KiwoomRestClient client,
                             @Value("${autostock.minute-archive.enabled:false}") boolean enabled,
                             @Value("${autostock.minute-archive.symbols:005930,000660,035420,035720,069500}") String symbolsCsv,
                             @Value("${autostock.minute-archive.dir:../data/minutes}") String dir) {
        this.client = client;
        this.enabled = enabled;
        this.symbols = List.of(symbolsCsv.split(","));
        this.outDir = Paths.get(dir);
    }

    /** 평일 15:45 KST — 장 마감(15:30) 후, 일일 리포트(15:50) 전. */
    @Scheduled(cron = "0 45 15 * * MON-FRI", zone = "Asia/Seoul")
    public void archiveToday() {
        if (!enabled) {
            return;
        }
        String today = LocalDate.now(MarketConstants.KST).format(DAY);
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            log.error("분봉 적재 디렉터리 생성 실패: {}", outDir, e);
            return;
        }
        for (String symbol : symbols) {
            try {
                int appended = archiveSymbol(symbol.trim(), today);
                log.info("분봉 적재: {} {}행 추가 (기준일 {})", symbol.trim(), appended, today);
            } catch (Exception e) {
                // 한 종목 실패가 나머지 적재를 막지 않는다 — 다음 날 페이지 창(2.3일)이 메워준다
                log.warn("분봉 적재 실패(내일 창으로 자연 복구): {} — {}", symbol, e.getMessage());
            }
        }
    }

    /** @return 새로 추가된 행 수 */
    @SuppressWarnings("unchecked")
    int archiveSymbol(String symbol, String day) throws IOException {
        Map<String, Object> response = client.call(TrId.MINUTE_CHART, "/api/dostk/chart",
                Map.of("stk_cd", symbol, "tic_scope", "1", "upd_stkpc_tp", "1"));
        Object list = response.get("stk_min_pole_chart_qry"); // 실측 확정 키
        if (!(list instanceof List<?> rows)) {
            log.warn("분봉 응답에 배열 없음({}): 키={}", symbol, response.keySet());
            return 0;
        }

        Path file = outDir.resolve(symbol + ".csv");
        String lastTime = lastArchivedTime(file);

        List<String[]> todays = new ArrayList<>();
        for (Object element : rows) {
            if (!(element instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> bar = (Map<String, Object>) raw;
            String time = String.valueOf(bar.getOrDefault("cntr_tm", ""));
            if (!time.startsWith(day)) {
                continue; // 오늘 행만 — 어제 행은 어제 실행이 이미 적재했다
            }
            if (lastTime != null && time.compareTo(lastTime) <= 0) {
                continue; // 멱등: 이미 적재된 시각
            }
            todays.add(new String[]{
                    time,
                    KiwoomNumbers.toBigDecimal(bar.get("open_pric")).abs().toPlainString(),
                    KiwoomNumbers.toBigDecimal(bar.get("high_pric")).abs().toPlainString(),
                    KiwoomNumbers.toBigDecimal(bar.get("low_pric")).abs().toPlainString(),
                    KiwoomNumbers.toBigDecimal(bar.get("cur_prc")).abs().toPlainString(),
                    String.valueOf(KiwoomNumbers.toLongOrZero(bar.get("trde_qty"))),
                    String.valueOf(KiwoomNumbers.toLongOrZero(bar.get("acc_trde_qty")))
            });
        }
        if (todays.isEmpty()) {
            return 0; // 휴장일 또는 이미 전부 적재됨
        }
        todays.sort(Comparator.comparing(a -> a[0])); // 시간 오름차순 보장

        StringBuilder sb = new StringBuilder();
        if (!Files.exists(file)) {
            sb.append(HEADER).append('\n');
        }
        for (String[] row : todays) {
            sb.append(String.join(",", row)).append('\n');
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return todays.size();
    }

    /** 기존 CSV의 마지막 행 시각(첫 컬럼). 파일 없거나 헤더뿐이면 null. */
    private String lastArchivedTime(Path file) throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = lines.size() - 1; i >= 1; i--) { // 0번은 헤더
            String line = lines.get(i).trim();
            if (!line.isEmpty()) {
                return line.substring(0, line.indexOf(','));
            }
        }
        return null;
    }
}

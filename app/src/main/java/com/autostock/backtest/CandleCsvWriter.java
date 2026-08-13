package com.autostock.backtest;

import com.autostock.common.event.Candle;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@link Candle} 목록을 {@link CandleCsvLoader}가 읽을 수 있는 CSV 포맷으로 저장하는 유틸.
 *
 * <p>키움 REST(ka10081)로 실측 수집한 일봉({@code KiwoomDailyChartService.fetchDaily})을
 * 파일로 떨어뜨려두면, 이 프로젝트의 기존 백테스트 파이프라인({@link CandleCsvLoader} →
 * {@link BacktestRunner}/{@link WalkForwardRunner})을 그대로 재사용해 키움 데이터로
 * 백테스트를 돌릴 수 있다 — 즉 "수집 → 저장 → 백테스트" 경로를 완성하는 마지막 조각이다.
 *
 * <p>{@link CandleCsvLoader}와 마찬가지로 종목코드는 파일명(확장자 제외)으로 표현하고
 * 본문에는 담지 않는다. 날짜는 ISO-8601({@code yyyy-MM-dd})로 쓴다.
 */
public final class CandleCsvWriter {

    private static final String HEADER = "date,open,high,low,close,volume";

    /**
     * 캔들 목록을 CSV로 저장한다. 호출자가 캔들을 이미 원하는 순서(보통 날짜 오름차순)로
     * 정렬해 넘겼다고 가정한다 — 이 클래스는 순서를 바꾸지 않고 그대로 쓴다.
     *
     * @param candles 저장할 캔들 목록(비어 있으면 헤더만 있는 파일을 만든다)
     * @param csvPath 저장 경로. 상위 디렉터리가 없으면 생성한다.
     */
    public void write(List<Candle> candles, Path csvPath) {
        try {
            Path parent = csvPath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(csvPath)) {
                writer.write(HEADER);
                writer.newLine();
                for (Candle candle : candles) {
                    writer.write(toLine(candle));
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("캔들 CSV 저장 실패: " + csvPath, e);
        }
    }

    private String toLine(Candle candle) {
        return String.join(",",
                candle.date().toString(),
                candle.open().toPlainString(),
                candle.high().toPlainString(),
                candle.low().toPlainString(),
                candle.close().toPlainString(),
                Long.toString(candle.volume()));
    }
}

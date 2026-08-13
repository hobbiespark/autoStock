package com.autostock.backtest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV 파일 하나를 읽어 {@link Candle} 목록으로 바꿔주는 아주 단순한 로더.
 *
 * <p>쉬운 설명: 증권사/데이터 벤더에서 받은 일봉 CSV(헤더가 date,open,high,low,close,volume인 파일)를
 * 한 줄씩 읽어 숫자/날짜로 파싱하는 역할만 한다. DB나 외부 API를 쓰지 않으므로
 * 스프링 빈으로 만들 필요가 없어 순수 자바 클래스로 두었다 — 단위 테스트도, 다른
 * 데이터 소스(DB 리플레이 등)로 교체하기도 쉽다.
 *
 * <p>종목코드 규칙: CSV 파일 자체에는 종목코드 컬럼이 없다고 가정하고,
 * 파일명(확장자 제외)을 종목코드로 사용한다. 예) {@code 005930.csv} → "005930".
 *
 * <p>날짜 형식은 ISO-8601({@code yyyy-MM-dd})을 가정한다.
 */
public final class CandleCsvLoader {

    private static final int COL_DATE = 0;
    private static final int COL_OPEN = 1;
    private static final int COL_HIGH = 2;
    private static final int COL_LOW = 3;
    private static final int COL_CLOSE = 4;
    private static final int COL_VOLUME = 5;

    /**
     * CSV 파일을 읽어 캔들 목록으로 반환한다. 파일 순서를 그대로 유지하므로
     * 호출자는 데이터가 이미 날짜순으로 정렬돼 있다고 가정할 수 있다(정렬 책임은 파일 준비 단계).
     *
     * @param csvPath 대상 CSV 경로
     * @return 파싱된 캔들 목록 (헤더 줄 제외)
     */
    public List<Candle> load(Path csvPath) {
        String symbol = extractSymbol(csvPath);
        List<Candle> candles = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(csvPath)) {
            String line = reader.readLine(); // 첫 줄은 헤더 — 건너뛴다
            if (line == null) {
                return candles; // 빈 파일
            }
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue; // 끝에 붙은 빈 줄 등은 조용히 무시
                }
                candles.add(parseLine(symbol, line));
            }
        } catch (IOException e) {
            // 백테스트 준비 단계에서 파일이 없거나 손상된 것은 즉시 실패시키는 게 낫다 —
            // 조용히 빈 데이터로 진행하면 "왜 결과가 이상하지"를 나중에 디버깅해야 한다.
            throw new UncheckedIOException("캔들 CSV 로드 실패: " + csvPath, e);
        }
        return candles;
    }

    private String extractSymbol(Path csvPath) {
        String fileName = csvPath.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private Candle parseLine(String symbol, String line) {
        String[] cols = line.split(",", -1);
        return new Candle(
                symbol,
                LocalDate.parse(cols[COL_DATE].trim()),
                new BigDecimal(cols[COL_OPEN].trim()),
                new BigDecimal(cols[COL_HIGH].trim()),
                new BigDecimal(cols[COL_LOW].trim()),
                new BigDecimal(cols[COL_CLOSE].trim()),
                Long.parseLong(cols[COL_VOLUME].trim())
        );
    }
}

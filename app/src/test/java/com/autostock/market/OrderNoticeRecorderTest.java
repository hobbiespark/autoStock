package com.autostock.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WS 주문체결통보 원문 상시 기록 — 하루 파일에 한 줄씩, 계좌번호(9201) 가림, 꺼져 있으면 아무것도 하지 않음, 기록 실패는 삼킴.
 */
class OrderNoticeRecorderTest {

    /** 2026-10-08 10:00:17 KST. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T01:00:17Z"), ZoneOffset.UTC);
    private static final String NOTICE = "{\"type\":\"00\",\"name\":\"주문체결\",\"item\":\"069500\",\"values\":"
            + "{\"9201\":\"1234567890\",\"9203\":\"0064217\",\"913\":\"확인\",\"904\":\"0063988\",\"905\":\"매수취소\"}}";

    @TempDir
    Path dir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode notice() throws Exception {
        return objectMapper.readTree(NOTICE);
    }

    @Test
    void 하루_파일에_한_줄씩_덧붙이고_계좌번호는_가린다() throws Exception {
        OrderNoticeRecorder recorder = new OrderNoticeRecorder(true, dir.toString(), objectMapper, CLOCK);

        recorder.record(notice());
        recorder.record(notice());

        List<String> lines = Files.readAllLines(dir.resolve("ws_orders_20261008.jsonl"), StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        JsonNode line = objectMapper.readTree(lines.get(0));
        assertEquals("2026-10-08T10:00:17+09:00", line.path("receivedAt").asText());
        assertEquals("****", line.path("data").path("values").path("9201").asText());
        assertEquals("0063988", line.path("data").path("values").path("904").asText());   // 원주문번호 등은 그대로
        assertFalse(lines.get(0).contains("1234567890"));
    }

    @Test
    void 원본_노드는_건드리지_않는다() throws Exception {
        OrderNoticeRecorder recorder = new OrderNoticeRecorder(true, dir.toString(), objectMapper, CLOCK);
        JsonNode original = notice();

        recorder.record(original);

        assertEquals("1234567890", original.path("values").path("9201").asText());   // 해석(RealMessageParser)은 원본을 쓴다
    }

    @Test
    void 꺼져_있으면_아무것도_남기지_않는다() throws Exception {
        new OrderNoticeRecorder(false, dir.toString(), objectMapper, CLOCK).record(notice());

        assertFalse(Files.exists(dir.resolve("ws_orders_20261008.jsonl")));
    }

    @Test
    void 기록할_수_없어도_예외를_던지지_않는다() throws Exception {
        Path file = dir.resolve("not-a-dir");
        Files.writeString(file, "x");
        OrderNoticeRecorder recorder = new OrderNoticeRecorder(true, file.resolve("sub").toString(), objectMapper, CLOCK);

        recorder.record(notice());
        recorder.record(notice());

        assertTrue(Files.isRegularFile(file));
    }
}

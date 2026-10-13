package com.autostock.market;

import com.autostock.common.util.MarketConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * WS 주문체결통보(type 00) 원문 상시 기록 — 실측(aiDoc/auto-measurement.md).
 *
 * <p>왜: 앱의 통보 해석은 9/11 실측 4건(접수·체결)에 맞춰져 있다. 취소 확인({@code 913=확인}, 10/7 첫 관측)·거부·부분 체결처럼
 * 일부러 만들기 어려운 통보는 생길 때를 기다려야 한다. 해석과 상관없이 받은 그대로 하루 파일에 한 줄씩 남겨 두면, 생긴 날의
 * 원문으로 해석을 확정할 수 있다(FID 904 원주문번호·905 주문구분 등 지금 읽지 않는 값까지).
 *
 * <p>계좌번호(FID {@value #ACCOUNT_FID})는 가린다 — 9/11 실측 원문이 계좌번호를 그대로 담은 채 공개 리포에 커밋된 적이 있다.
 * 기록 실패는 통보 처리를 막지 않는다(경고는 하루 한 번).
 */
@Component
public class OrderNoticeRecorder {

    private static final Logger log = LoggerFactory.getLogger(OrderNoticeRecorder.class);

    static final String ACCOUNT_FID = "9201";
    /** 가리는 FID — 계좌번호. */
    private static final List<String> MASKED_FIDS = List.of(ACCOUNT_FID);

    private final boolean enabled;
    private final Path dir;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private volatile LocalDate warnedDay;

    public OrderNoticeRecorder(@Value("${autostock.ws.order-notice-capture.enabled:false}") boolean enabled,
                               @Value("${autostock.ws.order-notice-capture.dir:../data/measured/ws_orders}") String dir,
                               ObjectMapper objectMapper, Clock clock) {
        this.enabled = enabled;
        this.dir = Path.of(dir);
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** REAL 메시지의 data 원소 하나(type 00)를 그날 파일 {@code ws_orders_{yyyyMMdd}.jsonl}에 한 줄로 덧붙인다. */
    public synchronized void record(JsonNode data) {
        if (!enabled || data == null) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(MarketConstants.KST));
        Path file = dir.resolve("ws_orders_%s.jsonl".formatted(now.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE)));
        try {
            JsonNode copy = data.deepCopy();
            if (copy.path("values") instanceof ObjectNode values) {
                for (String fid : MASKED_FIDS) {
                    if (values.hasNonNull(fid) && !values.get(fid).asText().isBlank()) {
                        values.put(fid, "****");
                    }
                }
            }
            ObjectNode line = objectMapper.createObjectNode();
            line.put("receivedAt", now.toOffsetDateTime().toString());
            line.set("data", copy);
            Files.createDirectories(dir);
            Files.writeString(file, objectMapper.writeValueAsString(line) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            LocalDate today = now.toLocalDate();
            if (!today.equals(warnedDay)) {
                warnedDay = today;
                log.warn("WS 주문통보 원문 기록 실패(오늘은 더 알리지 않는다): {} — {}", file.toAbsolutePath(), e.getMessage());
            }
        }
    }
}

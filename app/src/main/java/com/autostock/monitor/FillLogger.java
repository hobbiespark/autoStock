package com.autostock.monitor;

import com.autostock.common.event.Fill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 체결 알림 골격 — Phase 4에서 텔레그램 발송으로 교체.
 */
@Component
public class FillLogger {

    private static final Logger log = LoggerFactory.getLogger(FillLogger.class);

    @EventListener
    public void onFill(Fill fill) {
        log.info("체결: {} {} {}주 @ {}", fill.symbol(), fill.side(), fill.filledQuantity(), fill.fillPrice());
    }
}

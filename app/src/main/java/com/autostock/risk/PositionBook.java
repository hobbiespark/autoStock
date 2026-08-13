package com.autostock.risk;

import com.autostock.common.event.Fill;
import com.autostock.common.event.Side;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 인메모리 포지션북 — Fill 이벤트로 갱신.
 * 재시작 시 복원은 Phase 2 후반: 브로커 잔고 REST 재조회와 대사 (PLAN 9절).
 */
@Component
public class PositionBook {

    public record Position(long quantity, BigDecimal avgPrice) {
    }

    private final Map<String, Position> positions = new ConcurrentHashMap<>();

    @EventListener
    public void onFill(Fill fill) {
        positions.compute(fill.symbol(), (symbol, current) -> {
            long signed = fill.side() == Side.BUY ? fill.filledQuantity() : -fill.filledQuantity();
            if (current == null) {
                return signed > 0 ? new Position(signed, fill.fillPrice()) : null;
            }
            long newQty = current.quantity() + signed;
            if (newQty <= 0) {
                return null; // 청산
            }
            if (signed > 0) {
                // 평균단가 갱신 (매수 증가 시)
                BigDecimal totalCost = current.avgPrice().multiply(BigDecimal.valueOf(current.quantity()))
                        .add(fill.fillPrice().multiply(BigDecimal.valueOf(fill.filledQuantity())));
                BigDecimal avg = totalCost.divide(BigDecimal.valueOf(newQty), 2, RoundingMode.HALF_UP);
                return new Position(newQty, avg);
            }
            return new Position(newQty, current.avgPrice());
        });
    }

    public Position get(String symbol) {
        return positions.get(symbol);
    }

    public boolean holds(String symbol) {
        return positions.containsKey(symbol);
    }

    public int openPositionCount() {
        return positions.size();
    }
}

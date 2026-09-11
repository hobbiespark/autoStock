package com.autostock.portfolio;

import com.autostock.common.event.Fill;
import com.autostock.common.event.PositionRestored;
import com.autostock.common.event.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 포지션북 — "지금 무엇을 몇 주, 평균 얼마에 들고 있나"를 답하는 장부(ADR-6 재편으로
 * risk → portfolio 모듈로 이동, 배치 근거: 한도 판단은 risk 책임이지만 보유 포지션·손익
 * 원장 자체는 별도 도메인 — ARCHITECTURE.md 2절).
 *
 * <p>갱신 방법이 핵심이다: 누가 직접 값을 써넣는 게 아니라,
 * <b>체결(Fill) 이벤트가 도착할 때마다 스스로 갱신</b>한다.
 * 즉 이 장부는 "체결 이벤트 흐름을 접으면(fold) 나오는 현재 상태"다.
 * 같은 체결 이벤트를 처음부터 다시 흘리면(리플레이) 같은 장부가 재현된다 —
 * 백테스트가 라이브와 같은 코드로 도는 이유가 바로 이 구조다.
 *
 * <p>스레드 안전성: WS 수신 스레드와 스케줄러 스레드가 동시에 접근할 수 있으므로
 * {@link ConcurrentHashMap#compute}로 심볼 단위 원자적 갱신을 보장한다.
 *
 * <p>재시작 복원(운영 1일차 ⑨, 2026-09-11): 인메모리라 재시작하면 사라지는 한계를
 * {@link PositionRestored} 수신으로 보완한다 — LIVE 모드 기동 시 execution.PositionRestorer가
 * 브로커 잔고(kt00018)의 보유 배열을 읽어 발행한다. 이미 체결로 만들어진 포지션이 있으면
 * 덮어쓰지 않는다(브로커 스냅샷은 기동 직후에만 신뢰).
 */
@Component
public class PositionBook {

    private static final Logger log = LoggerFactory.getLogger(PositionBook.class);

    /**
     * 한 종목의 보유 상태.
     *
     * @param quantity 보유 수량 (항상 양수 — 0이 되면 맵에서 제거된다)
     * @param avgPrice 평균 매수 단가
     */
    public record Position(long quantity, BigDecimal avgPrice) {
    }

    /** key: 종목코드(예: "005930"), value: 보유 상태. 미보유 종목은 키 자체가 없다. */
    private final Map<String, Position> positions = new ConcurrentHashMap<>();

    /**
     * 체결 이벤트 반영. 매수는 수량 증가(+평단 재계산), 매도는 수량 감소.
     */
    @EventListener
    public void onFill(Fill fill) {
        positions.compute(fill.symbol(), (symbol, current) -> {
            // 매수는 +수량, 매도는 -수량으로 부호를 통일해 한 곳에서 처리
            long signed = fill.side() == Side.BUY ? fill.filledQuantity() : -fill.filledQuantity();

            if (current == null) {
                // 신규: 매수면 포지션 생성, (있을 수 없는) 미보유 매도면 무시(null 유지)
                return signed > 0 ? new Position(signed, fill.fillPrice()) : null;
            }

            long newQty = current.quantity() + signed;
            if (newQty <= 0) {
                return null; // 전량 청산 → 맵에서 키 제거 (compute가 null 반환 시 삭제)
            }

            if (signed > 0) {
                // 추가 매수 → 가중평균으로 평단 재계산:
                //   새 평단 = (기존수량×기존평단 + 체결수량×체결가) / 새 수량
                BigDecimal totalCost = current.avgPrice().multiply(BigDecimal.valueOf(current.quantity()))
                        .add(fill.fillPrice().multiply(BigDecimal.valueOf(fill.filledQuantity())));
                BigDecimal avg = totalCost.divide(BigDecimal.valueOf(newQty), 2, RoundingMode.HALF_UP);
                return new Position(newQty, avg);
            }
            // 부분 매도 → 수량만 줄고 평단은 그대로 (실현손익 계산은 별도 모듈 책임)
            return new Position(newQty, current.avgPrice());
        });
    }

    /**
     * 브로커 잔고 기반 포지션 시드(운영 1일차 ⑨). putIfAbsent — 이 JVM에서 이미 체결로
     * 만들어진 포지션이 있으면 그것이 더 최신이므로 덮어쓰지 않는다.
     */
    @EventListener
    public void onPositionRestored(PositionRestored restored) {
        if (restored.quantity() <= 0) {
            return;
        }
        Position previous = positions.putIfAbsent(restored.symbol(),
                new Position(restored.quantity(), restored.avgPrice()));
        if (previous == null) {
            log.info("포지션 복원(브로커 잔고): {} {}주 @ {}", restored.symbol(),
                    restored.quantity(), restored.avgPrice());
        }
    }

    /** @return 보유 상태, 미보유면 null */
    public Position get(String symbol) {
        return positions.get(symbol);
    }

    public boolean holds(String symbol) {
        return positions.containsKey(symbol);
    }

    /** 현재 보유 종목 수 — RiskGate의 동시 보유 한도 검사에 쓰인다. */
    public int openPositionCount() {
        return positions.size();
    }

    /** 전체 포지션 읽기 전용 스냅샷 — 대시보드 조회용. */
    public Map<String, Position> snapshot() {
        return Map.copyOf(positions);
    }
}

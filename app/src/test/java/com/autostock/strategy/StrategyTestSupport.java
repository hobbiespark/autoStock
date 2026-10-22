package com.autostock.strategy;

import com.autostock.common.event.Candle;
import com.autostock.common.event.Fill;
import com.autostock.common.event.Side;
import com.autostock.common.event.Signal;
import com.autostock.common.event.SignalDecision;
import com.autostock.common.event.StrategyRunCompleted;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.ClientOrderId;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import com.autostock.common.util.StockCode;
import com.autostock.portfolio.StrategyBook;
import com.autostock.portfolio.StrategyFillRepository;
import com.autostock.portfolio.StrategyPositionRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 다전략 시험 공통 — 이벤트 수집, 전략 장부, 체결 만들기. */
final class StrategyTestSupport {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T01:00:00Z"), ZoneOffset.UTC);
    static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

    final List<Object> events = new CopyOnWriteArrayList<>();
    final SignalOutbox outbox = new SignalOutbox(events::add, CLOCK);
    final StrategyBook book;
    private int seq;

    StrategyTestSupport() {
        StrategyPositionRepository positions = mock(StrategyPositionRepository.class);
        when(positions.findAll()).thenReturn(List.of());
        book = new StrategyBook(positions, mock(StrategyFillRepository.class), CLOCK, 0.00015, 0.0020);
    }

    void filled(String strategyId, String symbol, Side side, long qty, String price) {
        String id = ClientOrderId.generate(TODAY, strategyId, symbol, side, ++seq).value();
        book.onFill(new Fill(id, new BrokerOrderId("B" + seq), new StockCode(symbol), side, new Quantity(qty),
                new Price(new BigDecimal(price)), CLOCK.instant()));
    }

    List<Signal> signals() {
        outbox.drain();
        return events.stream().filter(Signal.class::isInstance).map(Signal.class::cast).toList();
    }

    List<SignalDecision> decisions() {
        outbox.drain();
        return events.stream().filter(SignalDecision.class::isInstance).map(SignalDecision.class::cast).toList();
    }

    List<StrategyRunCompleted> runs() {
        outbox.drain();
        return events.stream().filter(StrategyRunCompleted.class::isInstance).map(StrategyRunCompleted.class::cast).toList();
    }

    static Candle candle(String symbol, LocalDate day, String open, String high, String low, String close) {
        return new Candle(symbol, day, new BigDecimal(open), new BigDecimal(high), new BigDecimal(low),
                new BigDecimal(close), 1000L);
    }

    /** 평탄한 n봉(종가 close) + 마지막 전일 봉. */
    static List<Candle> flatThen(String symbol, int n, String close, Candle last) {
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(candle(symbol, TODAY.minusDays(n + 1 - i), close, close, close, close));
        }
        out.add(last);
        return out;
    }
}

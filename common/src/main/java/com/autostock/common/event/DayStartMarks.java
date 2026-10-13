package com.autostock.common.event;

import com.autostock.common.util.Price;
import com.autostock.common.util.StockCode;

import java.time.LocalDate;
import java.util.List;

/**
 * 거래일 장 시작 전 보유 종목의 기준가 — 일 손실 한도를 "오늘 움직임"으로 재기 위한 전일 종가(2026-10-13).
 *
 * <p>배경: 일 손실을 매입 평단 기준으로 재면, 며칠에 걸쳐 쌓인 평가손실이 청산하는 날 한꺼번에 "오늘 손실"이 된다.
 * 10/13 C3 국면 OFF 강제 청산에서 -1,097만 원(한도 -977만 원)으로 킬스위치가 켜졌다 — 오늘 하루 움직임은 작았다.
 * execution.PositionRestorer가 거래일 아침 잔고(kt00018)의 {@code pred_close_pric}로 이 이벤트를 한 번 발행하고,
 * risk.DailyPnlTracker가 밤사이 들고 온 보유분의 기준가를 전일 종가로 맞춘다. 앱이 밤새 켜져 있어도 매일 새로 잡힌다.
 *
 * @param day   기준가를 쓸 거래일(KST)
 * @param marks 보유 종목별 기준가 — 전일 종가를 모르면 {@link Mark#previousClose()}가 null
 */
public record DayStartMarks(LocalDate day, List<Mark> marks) {

    public DayStartMarks {
        marks = marks == null ? List.of() : List.copyOf(marks);
    }

    /**
     * @param symbol        종목
     * @param quantity      보유 수량
     * @param avgPrice      매입 평단(모르면 null) — 장부에 없는 종목을 새로 시드할 때만 쓴다
     * @param previousClose 전일 종가(모르면 null)
     */
    public record Mark(StockCode symbol, long quantity, Price avgPrice, Price previousClose) {
    }
}

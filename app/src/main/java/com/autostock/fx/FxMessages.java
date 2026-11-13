package com.autostock.fx;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 외화 알림 문구 — 설계 10장. 수백 자라 텔레그램 4,096자 한도(BE BC-40) 안이다. 매수를 권하지 않고 은행 앱 확인을 요청한다(FX-0b).
 */
final class FxMessages {

    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd");

    private FxMessages() {
    }

    private static DecimalFormat format(String pattern) {
        return new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.KOREA));
    }

    static String rate(BigDecimal v) {
        return format("#,##0.00").format(v);
    }

    static String signedRate(BigDecimal v) {
        return format("+#,##0.00;-#,##0.00").format(v);
    }

    static String won(long v) {
        return format("#,##0").format(v);
    }

    /** 직접매수 검토 알림. */
    static String review(FxCurrency currency, FxReservationEntity reservation, FxReviewRule.Result result,
                         BigDecimal publicRate, LocalDate observedOn, long exposureKrw, long monthlyCapKrw, long directBuyKrw) {
        String no = "R" + reservation.getId();
        return "[외화 검토] " + currency + " 직접매수 검토 조건에 닿았습니다\n"
                + "추정 살 때 환율 " + rate(result.estimate()) + " " + currency.unit()
                + " (공개 " + rate(publicRate) + " " + signedRate(reservation.getSpread()) + " 예약 때 차이, "
                + observedOn.format(MONTH_DAY) + " 기준)\n"
                + "기준선 " + rate(result.baseline()) + " = (신청 당시 " + rate(reservation.getRequestRate())
                + " + 예약 " + rate(reservation.getReserveRate()) + ") ÷ 2\n"
                + "예약 " + no + " 미체결 · 이번 달 노출 " + won(exposureKrw) + " / 상한 " + won(monthlyCapKrw)
                + "원 · 직접매수 " + won(directBuyKrw) + "원 가능\n"
                + "공개 환율은 하루 한 번 값이라 실제와 다를 수 있습니다. 사기 전에 은행 앱의 실제 환율을 확인해 주세요. "
                + "샀으면 /autostock_fx_buy, 예약이 체결됐으면 /autostock_fx_fill " + no;
    }
}

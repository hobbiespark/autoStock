package com.autostock.fx;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;

/**
 * 이번 달 노출액 — 설계 5장(D-28). 프레임워크·DB를 모르는 순수 계산이다.
 *
 * <p>노출액 = 이번 달에 체결로 입력된 금액(예약 체결 + 직접매수) + 아직 체결·취소되지 않은 모든 예약의 금액(신청한 달과 무관).
 * 지난달 예약이 이번 달에 체결돼도 상한을 넘는 직접매수 알림이 나가지 않게 열린 예약을 모두 넣어 보수적으로 잡는다.
 * 열린 예약은 체결 전이라 "이번 달 체결"과 겹쳐 세지 않는다.
 */
public final class FxExposure {

    /** 노출액 계산에 필요한 예약 값. */
    public record Reservation(FxReservationEntity.Status status, LocalDate filledOn, long amountKrw) {}

    /** 노출액 계산에 필요한 직접매수 값. */
    public record Buy(boolean done, LocalDate boughtOn, long amountKrw) {}

    private FxExposure() {
    }

    /** 그 달(KST 달력월)의 노출액(원). */
    public static long of(YearMonth month, Collection<Reservation> reservations, Collection<Buy> buys) {
        long total = 0;
        for (Reservation r : reservations) {
            if (r.status() == FxReservationEntity.Status.OPEN) {
                total += r.amountKrw();
            } else if (r.status() == FxReservationEntity.Status.FILLED && r.filledOn() != null
                    && YearMonth.from(r.filledOn()).equals(month)) {
                total += r.amountKrw();
            }
        }
        for (Buy b : buys) {
            if (b.done() && YearMonth.from(b.boughtOn()).equals(month)) {
                total += b.amountKrw();
            }
        }
        return total;
    }

    /** 이 금액을 더하면 상한을 넘는가(같으면 넘지 않음). */
    public static boolean exceedsCap(long exposureKrw, long addKrw, long monthlyCapKrw) {
        return exposureKrw + addKrw > monthlyCapKrw;
    }
}

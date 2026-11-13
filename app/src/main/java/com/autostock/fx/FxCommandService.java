package com.autostock.fx;

import com.autostock.common.util.MarketConstants;
import com.autostock.macrointel.EcosClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * 외화 입력 명령 다섯과 요약 — 설계 7·9장(D-28). 앱 봇·니보 경유 모두 {@code monitor.AppCommandRunner}가 이 부품을 부른다.
 *
 * <p>명령은 데브가 은행 앱에서 한 일을 <b>기록</b>만 한다. 돈을 움직이거나 주문하지 않고, 매수 여부를 권하지 않는다(FX-0a·0b).
 * 기록은 지우지 않고 취소 상태로 둔다.
 */
@Component
public class FxCommandService {

    /** 명령 ID(설명서 원천과 같아야 한다). */
    public static final List<String> COMMANDS = List.of("fx_view", "fx_reserve", "fx_fill", "fx_buy", "fx_cancel");
    static final int MAX_OPEN_ROWS = 10;

    static final String RESERVE_FORMAT = "형식: /autostock_fx_reserve 통화 예약환율 금액 신청당시환율 [만료일]\n예: /autostock_fx_reserve USD 1330 40000 1360";
    static final String FILL_FORMAT = "형식: /autostock_fx_fill 예약번호 [체결환율] [체결일]\n예: /autostock_fx_fill R12 1330";
    static final String BUY_FORMAT = "형식: /autostock_fx_buy 통화 환율 금액 [날짜]\n예: /autostock_fx_buy USD 1338 20000";
    static final String CANCEL_FORMAT = "형식: /autostock_fx_cancel 번호(예약 R12, 직접 B3)";

    /** 명령 결과 — 거절은 기록하지 않은 것이다. */
    public record Reply(boolean ok, String text) {}

    private final FxAccumulationProperties properties;
    private final EcosClient ecos;
    private final FxReservationRepository reservations;
    private final FxDirectBuyRepository buys;
    private final Clock clock;

    public FxCommandService(FxAccumulationProperties properties, EcosClient ecos, FxReservationRepository reservations,
                            FxDirectBuyRepository buys, Clock clock) {
        this.properties = properties;
        this.ecos = ecos;
        this.reservations = reservations;
        this.buys = buys;
        this.clock = clock;
    }

    /** 명령 하나를 실행한다. 모르는 명령이면 빈 값. */
    @Transactional
    public Optional<Reply> run(String commandId, String text) {
        return Optional.ofNullable(switch (commandId) {
            case "fx_view" -> new Reply(true, summary(YearMonth.from(today()), "[외화 적립] " + YearMonth.from(today())));
            case "fx_reserve" -> reserve(FxCommandText.parse(text));
            case "fx_fill" -> fill(FxCommandText.parse(text));
            case "fx_buy" -> buy(FxCommandText.parse(text));
            case "fx_cancel" -> cancel(FxCommandText.parse(text));
            default -> null;
        });
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(MarketConstants.KST));
    }

    private Reply reserve(FxCommandText.Parsed p) {
        if (p.currency() == null || p.numbers().size() < 3) {
            return new Reply(false, "통화와 숫자 셋(예약 환율·금액·신청 당시 환율)이 필요합니다.\n" + RESERVE_FORMAT);
        }
        BigDecimal reserveRate = p.numbers().get(0);
        BigDecimal requestRate = p.numbers().get(2);
        long amount;
        try {
            amount = p.numbers().get(1).longValueExact();
        } catch (ArithmeticException e) {
            return new Reply(false, "금액은 원 단위 정수로 보내 주세요.\n" + RESERVE_FORMAT);
        }
        Optional<String> problem = FxReviewRule.reservationProblem(requestRate, reserveRate, amount);
        if (problem.isPresent()) {
            return new Reply(false, problem.get() + ". 기록하지 않았습니다.\n" + RESERVE_FORMAT);
        }
        LocalDate today = today();
        LocalDate expires = p.date() == null ? today.plusMonths(1) : p.date();
        if (expires.isBefore(today)) {
            return new Reply(false, "만료일이 오늘보다 앞입니다. 기록하지 않았습니다.\n" + RESERVE_FORMAT);
        }
        long exposureBefore = exposure(YearMonth.from(today));
        Optional<EcosClient.Observation> ref = ecos.fetchLatest(FxCurrency.ECOS_STAT_CODE, p.currency().ecosItemCode());
        FxReservationEntity saved = reservations.save(new FxReservationEntity(p.currency(), today, requestRate, reserveRate, amount,
                ref.map(EcosClient.Observation::value).orElse(null), ref.map(EcosClient.Observation::date).orElse(null),
                expires, clock.instant()));
        StringBuilder out = new StringBuilder("예약 R").append(saved.getId()).append(" 기록: ").append(p.currency())
                .append(" 예약 ").append(FxMessages.rate(reserveRate)).append(" / 신청 당시 ").append(FxMessages.rate(requestRate))
                .append(" ").append(p.currency().unit()).append(" / ").append(FxMessages.won(amount)).append("원 / 만료 ").append(expires)
                .append("\n기준선 ").append(FxMessages.rate(FxReviewRule.baseline(requestRate, reserveRate)));
        if (saved.getSpread() == null) {
            out.append("\n공개 환율을 받지 못해 이 예약은 검토 알림을 내지 않습니다. 필요하면 취소(/autostock_fx_cancel R")
                    .append(saved.getId()).append(") 뒤 다시 입력해 주세요.");
        } else {
            out.append(" · 공개 ").append(FxMessages.rate(saved.getRefRate())).append("(").append(saved.getRefObservedOn())
                    .append(") 대비 차이 ").append(FxMessages.signedRate(saved.getSpread()));
        }
        if (FxExposure.exceedsCap(exposureBefore, amount, properties.monthlyCapKrw())) {
            out.append("\n주의: 이번 달 노출 ").append(FxMessages.won(exposureBefore + amount)).append("원이 상한 ")
                    .append(FxMessages.won(properties.monthlyCapKrw())).append("원을 넘습니다(기록은 했습니다).");
        }
        return new Reply(true, out.toString());
    }

    private Reply fill(FxCommandText.Parsed p) {
        Optional<FxReservationEntity> found = reservation(p);
        if (found.isEmpty()) {
            return new Reply(false, "예약 번호를 찾지 못했습니다.\n" + FILL_FORMAT);
        }
        FxReservationEntity r = found.get();
        if (r.getStatus() != FxReservationEntity.Status.OPEN) {
            return new Reply(false, "R" + r.getId() + "은(는) 이미 " + r.getStatus() + " 상태입니다. 기록하지 않았습니다.");
        }
        BigDecimal rate = p.numbers().isEmpty() ? r.getReserveRate() : p.numbers().get(0);
        if (rate.signum() <= 0) {
            return new Reply(false, "환율은 0보다 커야 합니다.\n" + FILL_FORMAT);
        }
        LocalDate on = p.date() == null ? today() : p.date();
        r.fill(on, rate, clock.instant());
        reservations.save(r);
        return new Reply(true, "예약 R" + r.getId() + " 체결 기록: " + r.getCurrency() + " " + FxMessages.rate(rate) + " "
                + r.getCurrency().unit() + " / " + FxMessages.won(r.getAmountKrw()) + "원 / " + on);
    }

    private Reply buy(FxCommandText.Parsed p) {
        if (p.currency() == null || p.numbers().size() < 2) {
            return new Reply(false, "통화와 숫자 둘(환율·금액)이 필요합니다.\n" + BUY_FORMAT);
        }
        BigDecimal rate = p.numbers().get(0);
        long amount;
        try {
            amount = p.numbers().get(1).longValueExact();
        } catch (ArithmeticException e) {
            return new Reply(false, "금액은 원 단위 정수로 보내 주세요.\n" + BUY_FORMAT);
        }
        if (rate.signum() <= 0 || amount <= 0) {
            return new Reply(false, "환율과 금액은 0보다 커야 합니다.\n" + BUY_FORMAT);
        }
        LocalDate on = p.date() == null ? today() : p.date();
        FxDirectBuyEntity saved = buys.save(new FxDirectBuyEntity(p.currency(), on, rate, amount, clock.instant()));
        long exposure = exposure(YearMonth.from(today()));
        return new Reply(true, "직접매수 B" + saved.getId() + " 기록: " + p.currency() + " " + FxMessages.rate(rate) + " "
                + p.currency().unit() + " / " + FxMessages.won(amount) + "원 / " + on
                + "\n이번 달 노출 " + FxMessages.won(exposure) + " / 상한 " + FxMessages.won(properties.monthlyCapKrw()) + "원");
    }

    private Reply cancel(FxCommandText.Parsed p) {
        if (p.reference() == null) {
            return new Reply(false, CANCEL_FORMAT);
        }
        if (p.reference().kind() == 'R') {
            Optional<FxReservationEntity> r = reservations.findById(p.reference().id());
            if (r.isEmpty() || r.get().getStatus() != FxReservationEntity.Status.OPEN) {
                return new Reply(false, "열린 예약 R" + p.reference().id() + "이(가) 없습니다. 기록을 바꾸지 않았습니다.");
            }
            r.get().cancel(clock.instant());
            reservations.save(r.get());
            return new Reply(true, "예약 R" + p.reference().id() + "을(를) 취소 상태로 바꿨습니다(기록은 남김).");
        }
        Optional<FxDirectBuyEntity> b = buys.findById(p.reference().id());
        if (b.isEmpty() || b.get().getStatus() != FxDirectBuyEntity.Status.DONE) {
            return new Reply(false, "직접매수 B" + p.reference().id() + "이(가) 없거나 이미 취소됐습니다. 기록을 바꾸지 않았습니다.");
        }
        b.get().cancel(clock.instant());
        buys.save(b.get());
        return new Reply(true, "직접매수 B" + p.reference().id() + "을(를) 취소 상태로 바꿨습니다(기록은 남김).");
    }

    /** 예약 번호 {@code R12}(숫자만 보내면 금액·환율과 헷갈려 받지 않는다). */
    private Optional<FxReservationEntity> reservation(FxCommandText.Parsed p) {
        if (p.reference() == null || p.reference().kind() != 'R') {
            return Optional.empty();
        }
        return reservations.findById(p.reference().id());
    }

    /** 이번 달 노출액(설계 5장). */
    long exposure(YearMonth month) {
        return FxExposure.of(month,
                reservations.findByStatusOrFilledOnBetween(FxReservationEntity.Status.OPEN, month.atDay(1), month.atEndOfMonth())
                        .stream().map(FxReservationEntity::exposureView).toList(),
                buys.findByBoughtOnBetweenOrderByBoughtOnAscIdAsc(month.atDay(1), month.atEndOfMonth())
                        .stream().map(FxDirectBuyEntity::exposureView).toList());
    }

    /**
     * 그 달 요약 — {@code fx_view}와 월말 정산이 함께 쓴다. 통화별 체결(예약·직접) 원화·외화 합계와 가중 평균 환율, 노출액과 상한,
     * 열린 예약(최대 {@value #MAX_OPEN_ROWS}건), 만료일이 지난 미입력 예약.
     */
    String summary(YearMonth month, String title) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        List<FxReservationEntity> monthReservations = reservations.findByStatusOrFilledOnBetween(
                FxReservationEntity.Status.OPEN, from, to);
        List<FxDirectBuyEntity> monthBuys = buys.findByBoughtOnBetweenOrderByBoughtOnAscIdAsc(from, to);
        StringBuilder out = new StringBuilder(title).append("\n");
        for (FxCurrency c : FxCurrency.values()) {
            long krw = 0;
            BigDecimal foreign = BigDecimal.ZERO;
            int count = 0;
            boolean direct = false;
            for (FxReservationEntity r : monthReservations) {
                if (r.getCurrency() == c && r.getStatus() == FxReservationEntity.Status.FILLED) {
                    krw += r.getAmountKrw();
                    foreign = foreign.add(foreignAmount(c, r.getAmountKrw(), r.getFilledRate()));
                    count++;
                }
            }
            for (FxDirectBuyEntity b : monthBuys) {
                if (b.getCurrency() == c && b.getStatus() == FxDirectBuyEntity.Status.DONE) {
                    krw += b.getAmountKrw();
                    foreign = foreign.add(foreignAmount(c, b.getAmountKrw(), b.getRate()));
                    count++;
                    direct = true;
                }
            }
            out.append(c).append(": ");
            if (count == 0) {
                out.append("이번 달 체결 없음");
            } else {
                out.append("체결 ").append(count).append("건 ").append(FxMessages.won(krw)).append("원 → ")
                        .append(foreign.setScale(2, RoundingMode.HALF_UP).toPlainString()).append(c == FxCurrency.USD ? "달러" : "엔")
                        .append(" (평균 ").append(FxMessages.rate(averageRate(c, krw, foreign))).append(" ").append(c.unit()).append(")");
            }
            if (!direct) {
                out.append(" · 직접 없음");
            }
            out.append("\n");
        }
        long exposure = FxExposure.of(month, monthReservations.stream().map(FxReservationEntity::exposureView).toList(),
                monthBuys.stream().map(FxDirectBuyEntity::exposureView).toList());
        out.append("노출 ").append(FxMessages.won(exposure)).append(" / 상한 ").append(FxMessages.won(properties.monthlyCapKrw())).append("원\n");
        List<FxReservationEntity> open = reservations.findByStatusInOrderByRequestedOnAscIdAsc(EnumSet.of(FxReservationEntity.Status.OPEN));
        if (open.isEmpty()) {
            out.append("열린 예약 없음");
        } else {
            out.append("열린 예약 ").append(open.size()).append("건");
            open.stream().limit(MAX_OPEN_ROWS).forEach(r -> out.append("\n R").append(r.getId()).append(" ").append(r.getCurrency())
                    .append(" 예약 ").append(FxMessages.rate(r.getReserveRate())).append(" ").append(FxMessages.won(r.getAmountKrw()))
                    .append("원 만료 ").append(r.getExpiresOn()));
            if (open.size() > MAX_OPEN_ROWS) {
                out.append("\n 외 ").append(open.size() - MAX_OPEN_ROWS).append("건");
            }
            List<String> expired = open.stream().filter(r -> r.getExpiresOn().isBefore(today())).map(r -> "R" + r.getId()).toList();
            if (!expired.isEmpty()) {
                out.append("\n만료일이 지난 예약 ").append(String.join(", ", expired))
                        .append(": 체결됐으면 /autostock_fx_fill, 아니면 /autostock_fx_cancel 로 알려 주세요.");
            }
        }
        return out.toString();
    }

    /** 원화로 산 외화 양. 엔은 원/100엔이라 100을 곱한다. */
    static BigDecimal foreignAmount(FxCurrency c, long krw, BigDecimal rate) {
        BigDecimal units = BigDecimal.valueOf(krw).divide(rate, 6, RoundingMode.HALF_UP);
        return c == FxCurrency.JPY ? units.multiply(BigDecimal.valueOf(100)) : units;
    }

    /** 가중 평균 환율(원/1달러, 원/100엔). */
    static BigDecimal averageRate(FxCurrency c, long krw, BigDecimal foreign) {
        BigDecimal perUnit = BigDecimal.valueOf(krw).divide(foreign, 6, RoundingMode.HALF_UP);
        return c == FxCurrency.JPY ? perUnit.multiply(BigDecimal.valueOf(100)) : perUnit;
    }
}

package com.autostock.ipo;

import com.autostock.common.util.SecretMasking;
import com.autostock.kiwoom.KiwoomApiException;
import com.autostock.kiwoom.KiwoomLiveSellClient;
import com.autostock.kiwoom.KiwoomTimeoutException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * {@link IpoLiveBroker}의 키움 실전 구현 — {@link KiwoomLiveSellClient}의 예외를 결과 셋으로 바꾼다.
 *
 * <ul>
 *   <li>응답 시간 초과({@link KiwoomTimeoutException})·주문번호 없는 응답·그 밖의 통신 예외 → 결과 불명(UNKNOWN)</li>
 *   <li>키움 오류 응답({@link KiwoomApiException}) → 거부(REJECTED)</li>
 * </ul>
 */
final class KiwoomIpoLiveBroker implements IpoLiveBroker {

    private final KiwoomLiveSellClient client;

    KiwoomIpoLiveBroker(KiwoomLiveSellClient client) {
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public SellResult sellLimit(String symbol, long quantity, BigDecimal limitPrice) {
        try {
            String orderNo = client.sellLimit(symbol, quantity, limitPrice);
            return orderNo == null
                    ? new SellResult(Outcome.UNKNOWN, null, "응답에 주문번호 없음")
                    : new SellResult(Outcome.ACCEPTED, orderNo, null);
        } catch (KiwoomTimeoutException e) {
            return new SellResult(Outcome.UNKNOWN, null, "응답 시간 초과");
        } catch (KiwoomApiException e) {
            return new SellResult(Outcome.REJECTED, null, SecretMasking.mask(String.valueOf(e.getMessage())));
        } catch (IllegalArgumentException e) {
            return new SellResult(Outcome.REJECTED, null, "주문 값 오류(" + e.getMessage() + ") — 보내지 않음");
        } catch (RuntimeException e) {
            return new SellResult(Outcome.UNKNOWN, null, e.getClass().getSimpleName());
        }
    }

    @Override
    public long sellableQuantity(String symbol) {
        return client.holding(symbol).sellable();
    }

    @Override
    public List<String> outstandingOrderNos(String symbol) {
        return client.outstandingOrders(symbol).stream().map(KiwoomLiveSellClient.OpenOrder::orderNo).toList();
    }
}

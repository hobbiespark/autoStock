package com.autostock.trading;

import java.util.Set;

/** VALUNIVO 612(D301): 미체결 주문 상태 집합의 공개 창구. 기준은 {@link OpenOrderQueryAdapter#OPEN_STATUSES} 하나다(갈라지지 않게 그대로 내보낸다). */
public final class OpenOrders {
    public static final Set<OrderStatus> STATUSES = OpenOrderQueryAdapter.OPEN_STATUSES;

    private OpenOrders() {
    }
}

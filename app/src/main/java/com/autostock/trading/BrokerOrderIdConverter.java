package com.autostock.trading;

import com.autostock.common.util.BrokerOrderId;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** orders.broker_order_id(varchar) ↔ BrokerOrderId. 접수 전(null)은 null 그대로 둔다. */
@Converter
class BrokerOrderIdConverter implements AttributeConverter<BrokerOrderId, String> {

    @Override
    public String convertToDatabaseColumn(BrokerOrderId attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public BrokerOrderId convertToEntityAttribute(String column) {
        return column == null || column.isBlank() ? null : new BrokerOrderId(column);
    }
}

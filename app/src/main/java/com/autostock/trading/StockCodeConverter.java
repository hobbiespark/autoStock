package com.autostock.trading;

import com.autostock.common.util.StockCode;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * orders.symbol(varchar) ↔ StockCode. 형식이 틀린 행은 읽을 때 예외로 드러난다(조용히 넘기지 않는다, §2.1).
 */
@Converter
class StockCodeConverter implements AttributeConverter<StockCode, String> {

    @Override
    public String convertToDatabaseColumn(StockCode attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public StockCode convertToEntityAttribute(String column) {
        return column == null ? null : new StockCode(column);
    }
}

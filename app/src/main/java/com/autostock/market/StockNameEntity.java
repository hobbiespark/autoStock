package com.autostock.market;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 종목명 사전 한 행(V10 {@code stock_names}) — {@link StockNameDirectory}만 쓴다. 쓰기는 {@link StockNameRepository#upsert}. */
@Entity
@Table(name = "stock_names")
public class StockNameEntity {

    @Id
    @Column(name = "symbol", nullable = false, length = 12)
    private String symbol;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "source", nullable = false, length = 16)
    private String source;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA 전용. */
    protected StockNameEntity() {
    }

    /** 조립용(테스트) — 운영 쓰기는 {@link StockNameRepository#upsert}만 쓴다. */
    StockNameEntity(String symbol, String name, String source, Instant updatedAt) {
        this.symbol = symbol;
        this.name = name;
        this.source = source;
        this.updatedAt = updatedAt;
    }

    public String getSymbol() { return symbol; }
    public String getName() { return name; }
    public String getSource() { return source; }
    public Instant getUpdatedAt() { return updatedAt; }
}

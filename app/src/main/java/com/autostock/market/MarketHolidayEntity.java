package com.autostock.market;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 시장 휴장일 한 건 — {@code market_holidays} 테이블 매핑(V3 마이그레이션 참고).
 *
 * <p>휴장일 날짜({@link #holidayDate})를 그대로 기본키로 쓴다 — 하루에 휴장 사유는
 * 하나만 있으면 충분하고(공휴일이 겹치는 날도 결국 "쉬는 날" 하나), 별도 대리키(surrogate key)를
 * 둘 이유가 없다.
 *
 * <p>{@link #source}는 이 레코드가 어디서 왔는지 구분한다:
 * <ul>
 *   <li>{@code "DATA_GO_KR"} — 공공데이터포털 특일 정보 API(getRestDeInfo)로 자동 동기화된 공휴일</li>
 *   <li>{@code "MANUAL"} — 운영자가 손으로 등록한 거래소 자체 휴장(연말휴장 등).
 *       등록 방법은 {@link HolidaySyncService} 클래스 설명의 "거래소 자체 휴장 보완" 절 참고.</li>
 * </ul>
 */
@Entity
@Table(name = "market_holidays")
public class MarketHolidayEntity {

    @Id
    @Column(name = "holiday_date", nullable = false)
    private LocalDate holidayDate;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "source", nullable = false, length = 20)
    private String source;

    /** true면 "법정 공휴일은 아니지만 거래소가 자체적으로 정한 휴장일"(예: 12/31 연말휴장). */
    @Column(name = "is_market_closure", nullable = false)
    private boolean marketClosure;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;

    /** JPA 스펙상 필요한 기본 생성자 — 애플리케이션 코드에서 직접 호출하지 않는다. */
    protected MarketHolidayEntity() {
    }

    public MarketHolidayEntity(LocalDate holidayDate, String name, String source,
                                boolean marketClosure, Instant syncedAt) {
        this.holidayDate = holidayDate;
        this.name = name;
        this.source = source;
        this.marketClosure = marketClosure;
        this.syncedAt = syncedAt;
    }

    /**
     * API 재동기화 시 이름과 동기화 시각만 갱신한다. {@link #source}·{@link #marketClosure}는
     * 최초 등록 값을 그대로 유지한다 — 어차피 이 메서드는 {@code HolidaySyncService}가
     * source="DATA_GO_KR" 레코드에만 쓰므로 값이 바뀔 일이 없다(값을 다시 안 받는 것은
     * 의도적인 단순화다).
     */
    public void updateFromSync(String name, Instant syncedAt) {
        this.name = name;
        this.syncedAt = syncedAt;
    }

    public LocalDate getHolidayDate() {
        return holidayDate;
    }

    public String getName() {
        return name;
    }

    public String getSource() {
        return source;
    }

    public boolean isMarketClosure() {
        return marketClosure;
    }

    public Instant getSyncedAt() {
        return syncedAt;
    }
}

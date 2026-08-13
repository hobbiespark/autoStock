-- 시장 휴장일 테이블(marketdata 모듈).
--
-- 공공데이터포털 "특일 정보"(한국천문연구원 SpcdeInfoService, getRestDeInfo 오퍼레이션)를
-- 매년 11월 1일 자동 동기화해 채운다(HolidaySyncService). 다만 이 API는 "법정 공휴일"만
-- 내려주므로, 거래소가 자체적으로 정하는 연말휴장(12/31)·임시휴장 같은 날은 API 응답에
-- 없다 — 그런 날은 source='MANUAL'로 별도 수동 등록해야 한다(HolidaySyncService Javadoc
-- "거래소 자체 휴장 보완" 절 참고).
CREATE TABLE market_holidays (
    holiday_date        DATE         PRIMARY KEY,          -- 휴장일(공휴일) 날짜. 하루에 하나만 존재하므로 그대로 PK로 사용
    name                 VARCHAR(100) NOT NULL,              -- 특일명(예: "설날", "연말휴장")
    source                VARCHAR(20)  NOT NULL,              -- 'DATA_GO_KR'(API 자동 동기화) | 'MANUAL'(수동 등록)
    is_market_closure    BOOLEAN      NOT NULL DEFAULT FALSE, -- true면 "공휴일은 아니지만 거래소가 자체적으로 정한 휴장일"(12/31 연말휴장 등)
    synced_at             TIMESTAMPTZ  NOT NULL               -- 이 레코드가 마지막으로 갱신된 시각(API 동기화 시각 또는 수동 등록 시각)
);

-- MarketCalendarService는 "연도 단위"(findByHolidayDateBetween)로 조회한다. holiday_date가
-- 이미 PK(고유 인덱스)라 BETWEEN 범위 스캔에 그대로 활용되므로 별도 인덱스는 추가하지 않는다.

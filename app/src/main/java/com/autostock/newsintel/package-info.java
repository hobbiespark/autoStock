/**
 * 뉴스 인텔리전스 모듈 (Phase 7): 뉴스 수집 → sidecar-nlp(KR-FinBERT) 스코어링
 * → NewsSentiment 발행. 단독 매매 근거 금지, 필터 전용 (PLAN 5절).
 */
@org.springframework.modulith.ApplicationModule(displayName = "news-intel")
package com.autostock.newsintel;

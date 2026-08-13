/**
 * 주문 실행 모듈: OrderRequest → 키움 주문 API → Fill 발행.
 * 멱등성(idempotencyKey 중복 거부)과 미체결 추적이 핵심 책임.
 */
@org.springframework.modulith.ApplicationModule(displayName = "execution")
package com.autostock.execution;

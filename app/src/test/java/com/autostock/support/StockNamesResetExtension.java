package com.autostock.support;

import com.autostock.common.util.StockNames;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * 모든 테스트 전후로 종목명 사전({@link StockNames}, 정적 저장소)을 비운다 — 한 테스트가 배운 이름이 다음 테스트의
 * 기대 문구를 바꾸지 않게 한다(2026-10-02 도입 때 실제로 테스트 순서에 따라 결과가 달라졌다).
 * 등록: {@code META-INF/services/org.junit.jupiter.api.extension.Extension} + {@code junit-platform.properties}의
 * 자동 감지 설정. 이름이 필요한 테스트는 각자 {@link StockNames#learn}으로 넣는다.
 */
public class StockNamesResetExtension implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        StockNames.resetForTest();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        StockNames.resetForTest();
    }
}

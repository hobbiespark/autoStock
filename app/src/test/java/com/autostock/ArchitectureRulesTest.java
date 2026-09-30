package com.autostock;

import com.autostock.common.event.CancelRequest;
import com.autostock.common.event.OrderRequest;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 모듈 내부까지 내려가는 설계 규칙(docs/ARCHITECTURE.md 2·5·11·13절) — ModularityTests(모듈 경계)가
 * 잡지 못하는 방향 규칙을 고정한다. 규칙을 어기는 코드는 빌드에서 실패한다.
 */
class ArchitectureRulesTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.autostock");
    }

    @Test
    void strategy는_브로커와_주문_모듈을_모른다() {
        // 설계 규칙 1·2·5: Strategy는 Signal까지만, 주문·브로커 연동은 다른 모듈의 몫
        noClasses().that().resideInAPackage("com.autostock.strategy..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.autostock.kiwoom..", "com.autostock.execution..", "com.autostock.trading..")
                .check(classes);
    }

    @Test
    void strategy는_주문_이벤트를_만들지_않는다() {
        // 설계 규칙 1·3: 주문은 Signal이 RiskGate를 통과한 뒤에만 생긴다
        noClasses().that().resideInAPackage("com.autostock.strategy..")
                .should().dependOnClassesThat().areAssignableTo(OrderRequest.class)
                .orShould().dependOnClassesThat().areAssignableTo(CancelRequest.class)
                .check(classes);
    }

    @Test
    void 주문_요청은_risk와_오프라인_backtest만_생성한다() {
        noClasses().that().resideOutsideOfPackages("com.autostock.risk..", "com.autostock.backtest..")
                .should().callConstructorWhere(target(owner(assignableTo(OrderRequest.class))))
                .check(classes);
    }

    @Test
    void market에서_브로커_모듈을_아는_것은_어댑터뿐이다() {
        // 설계 규칙 5·7: Kiwoom은 Adapter로만 — 시세는 MarketDataPort, 실시간은 WS 클라이언트가 번역한다
        noClasses().that().resideInAPackage("com.autostock.market..")
                .and().doNotHaveSimpleName("KiwoomMarketDataAdapter")
                .and().doNotHaveSimpleName("KiwoomWebSocketClient")
                .should().dependOnClassesThat().resideInAPackage("com.autostock.kiwoom..")
                .check(classes);
    }

    @Test
    void risk는_브로커_어댑터를_모른다() {
        noClasses().that().resideInAPackage("com.autostock.risk..")
                .should().dependOnClassesThat().resideInAPackage("com.autostock.kiwoom..")
                .check(classes);
    }

    @Test
    void execution은_trading을_모른다() {
        // ARCHITECTURE.md 2절: trading → execution(BrokerPort) 단방향
        noClasses().that().resideInAPackage("com.autostock.execution..")
                .should().dependOnClassesThat().resideInAPackage("com.autostock.trading..")
                .check(classes);
    }

    @Test
    void portfolio는_리프_모듈이다() {
        noClasses().that().resideInAPackage("com.autostock.portfolio..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.autostock.risk..", "com.autostock.trading..",
                        "com.autostock.execution..", "com.autostock.strategy..")
                .check(classes);
    }

    @Test
    void 순수_계산_코어는_프레임워크와_시계를_모른다() {
        // ARCHITECTURE.md 5절 Functional Core: 백테스트와 라이브가 같은 계산을 공유하는 전제
        classes().that().haveSimpleNameEndingWith("Math").and().resideInAPackage("com.autostock.strategy..")
                .or().haveSimpleName("KrxTickSize")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java.lang..", "java.math..", "java.util..", "java.time..",
                        "com.autostock.strategy..", "com.autostock.risk..")
                .check(classes);
        noClasses().that().haveSimpleNameEndingWith("Math").and().resideInAPackage("com.autostock.strategy..")
                .or().haveSimpleName("KrxTickSize")
                .should().dependOnClassesThat().areAssignableTo(Clock.class)
                .check(classes);
    }

    @Test
    void cron_스케줄은_시간대를_명시한다() {
        // A5(2026-09-30): zone이 없으면 JVM 기본 시간대를 따른다. 운영 PC(KST)와 다른 시간대에서 돌면 장 시간 배치가 어긋난다.
        methods().that().areAnnotatedWith(Scheduled.class)
                .should(new ArchCondition<JavaMethod>("cron이면 zone을 명시한다") {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        Scheduled scheduled = method.getAnnotationOfType(Scheduled.class);
                        boolean ok = scheduled.cron().isEmpty() || !scheduled.zone().isEmpty();
                        events.add(new SimpleConditionEvent(method, ok,
                                method.getFullName() + " cron=\"" + scheduled.cron() + "\" zone=\"" + scheduled.zone() + "\""));
                    }
                })
                .check(classes);
    }
}

package com.autostock.config.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.CompositeConverter;
import com.autostock.common.util.SecretMasking;

/**
 * 로그백 레벨의 시크릿 마스킹 컨버터 — 심층 방어 2겹 중 <b>최후 방어선</b>.
 *
 * <p>DART 키(URL 쿼리 {@code crtfc_key=}), 텔레그램 봇 토큰(URL 경로 {@code /bot{token}/}),
 * 키움 앱키·시크릿·접근토큰(헤더/바디)은 WebClient 예외 메시지·디버그 로그·스택트레이스를
 * 통해 소스 코드에서 미처 걸러내지 못한 채 로그로 흘러나갈 수 있다. 클라이언트별 소스 지점
 * 방어({@code DartClient}, {@code TelegramNotifier} 등의 catch 블록에서
 * {@code com.autostock.common.util.SecretMasking} 적용)가 1차 방어선이고, 이 컨버터는 그걸
 * 빠뜨렸거나 예측하지 못한 경로(서드파티 라이브러리 디버그 로그 등)로 새는 것까지 콘솔/파일에
 * 실제로 찍히기 직전 최종적으로 걸러낸다.
 *
 * <p>{@code %msg}를 감싸는 컴포짓(composite) 변환어다({@code %replace}와 같은 용법).
 * {@code logback-spring.xml}에 등록해 쓴다:
 * <pre>
 *   &lt;conversionRule conversionWord="mask"
 *       converterClass="com.autostock.config.logging.SecretMaskingConverter" /&gt;
 *   ...
 *   %mask(%msg)%n%maskedEx
 * </pre>
 *
 * <p>예외 스택트레이스({@code %ex}/{@code %wEx})는 이 클래스로 감쌀 수 없다 — 로그백
 * 패턴 컴파일러가 예외 변환어를 컴포짓 변환어 안에 중첩하는 것을 허용하지 않는다
 * ({@code %mask(%ex)}처럼 쓰면 {@code PARSER_ERROR}가 난다). 그래서 예외 쪽은 별도
 * 클래스 {@link ThrowableMaskingConverter}(같은 패키지)가 {@code %ex} 자체를 대체하는
 * 방식으로 담당한다 — 두 클래스를 합쳐야 메시지+예외 출력 전체가 방어된다.
 *
 * <p>마스킹 규칙 자체는 여기서 정의하지 않는다 —
 * {@code com.autostock.common.util.SecretMasking#mask(String)}(common 모듈, 순수 자바)를
 * 그대로 위임해 규칙이 한 곳에서만 관리되도록 한다(소스 지점 방어와 최후 방어선이 서로 다른
 * 규칙을 쓰면 둘 중 하나가 놓친 패턴을 다른 하나도 놓칠 수 있다).
 *
 * @see ThrowableMaskingConverter 예외 스택트레이스({@code %ex}) 쪽 최후 방어선
 */
public class SecretMaskingConverter extends CompositeConverter<ILoggingEvent> {

    @Override
    protected String transform(ILoggingEvent event, String in) {
        return SecretMasking.mask(in);
    }
}

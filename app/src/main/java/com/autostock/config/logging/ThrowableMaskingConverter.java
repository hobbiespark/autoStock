package com.autostock.config.logging;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.autostock.common.util.SecretMasking;

/**
 * {@code %ex}/{@code %wEx}(스택트레이스 전체 — 예외 메시지 + 각 프레임 + cause 체인) 출력을
 * 마스킹하는 로그백 변환어. {@link SecretMaskingConverter}의 클래스 Javadoc "예외
 * 스택트레이스" 절 참고 — 예외 변환어는 컴포짓 변환어 안에 중첩할 수 없어서
 * ({@code %mask(%ex)}는 파서 오류) {@link ThrowableProxyConverter}를 직접 상속해 렌더링
 * 결과물을 그 자리에서 가로채 마스킹한다.
 *
 * <p>DART/텔레그램/키움 WebClient 호출이 던지는 예외(예: {@code WebClientResponseException})의
 * 메시지에는 호출한 전체 URL이 그대로 들어있는 경우가 많다 — DART {@code crtfc_key}, 텔레그램
 * 봇 토큰이 여기 실려 새어나갈 수 있다. 이 컨버터는 그런 경로까지 포함해 콘솔/파일에 실제로
 * 찍히기 직전 최종적으로 마스킹한다({@code common.util.SecretMasking}의 규칙을 그대로 재사용).
 *
 * <p>{@code logback-spring.xml}에 등록해 쓴다({@code %ex} 자체를 대체하는 변환어라 괄호 없이
 * 그대로 쓴다 — 컴포짓 변환어가 아니다):
 * <pre>
 *   &lt;conversionRule conversionWord="maskedEx"
 *       converterClass="com.autostock.config.logging.ThrowableMaskingConverter" /&gt;
 *   ...
 *   %mask(%msg)%n%maskedEx
 * </pre>
 */
public class ThrowableMaskingConverter extends ThrowableProxyConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return SecretMasking.mask(super.convert(event));
    }
}

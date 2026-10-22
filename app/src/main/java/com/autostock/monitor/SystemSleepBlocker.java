package com.autostock.monitor;

import com.sun.jna.platform.win32.Kernel32;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Windows 유휴 절전 차단 — {@code SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED)}.
 *
 * <p><b>화면 켜짐 요청까지 거는 이유</b>(2026-10-12 실측, aiDoc/sleep-resume.md 5절): 운영 PC는 S3 절전이 없고
 * 최신 대기 모드(Modern Standby, S0 저전원 유휴)를 쓴다. {@code ES_SYSTEM_REQUIRED}만 건 채로 마지막 입력 1시간 뒤
 * Windows가 대기 모드에 들어갔고(08:14), 앱은 대기 속에서 65분 더 돌다 멈췄다(09:19~18:05). 화면이 유휴로 꺼지지 않게
 * 막아야 대기 진입 자체가 없다. 화면은 앱이 절전을 막는 동안(거래일 07:30~16:00)만 켜 두고, 끝나면 푼다.
 * 끄려면 {@code autostock.session.keep-display-on=false} — S3 절전 PC라면 시스템 요청만으로 충분하다.
 *
 * <p>이 요청은 호출한 스레드에 붙고 그 스레드가 끝나면 사라진다. 그래서 스케줄러 풀이 아니라 앱과 수명을 같이 하는
 * 전용 스레드 하나에서만 호출한다. Windows가 아니면(테스트·CI) 아무것도 하지 않는다.
 *
 * <p>막는 것은 <b>유휴</b>로 인한 절전·대기뿐이다 — 사용자가 직접 절전을 누르거나 노트북 덮개를 닫는 것은 막지 못하고,
 * 이미 대기에 들어간 PC를 깨우지도 못한다(밤사이 대기는 전원 설정 몫 — RUNBOOK Phase 0.9).
 * 동작 확인: 장중에 관리자 PowerShell에서 {@code powercfg /requests}를 실행하면 DISPLAY·SYSTEM 항목에 java.exe가 보여야 한다.
 */
@Component
public class SystemSleepBlocker {

    private static final Logger log = LoggerFactory.getLogger(SystemSleepBlocker.class);

    static final int ES_CONTINUOUS = 0x80000000;
    static final int ES_SYSTEM_REQUIRED = 0x00000001;
    static final int ES_DISPLAY_REQUIRED = 0x00000002;

    private final boolean windows =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    private final boolean keepDisplayOn;
    private final ExecutorService owner = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "sleep-blocker");
        thread.setDaemon(true);
        return thread;
    });

    /** 마지막으로 요청한 상태 — 같은 요청을 30초마다 반복하지 않기 위한 것. null이면 아직 요청 전. */
    private volatile Boolean requested;

    public SystemSleepBlocker(@Value("${autostock.session.keep-display-on:true}") boolean keepDisplayOn) {
        this.keepDisplayOn = keepDisplayOn;
    }

    /** true면 유휴 절전을 막고, false면 막던 것을 푼다. 상태가 바뀔 때만 실제로 호출한다. */
    public void preventSleep(boolean prevent) {
        if (!windows || Boolean.valueOf(prevent).equals(requested)) {
            return;
        }
        requested = prevent;
        owner.execute(() -> apply(prevent, "장외 대기(STANDBY)"));
    }

    /** {@code SetThreadExecutionState}에 넘길 값 — 막을 때는 시스템(+화면) 요청, 풀 때는 {@code ES_CONTINUOUS}만(앞선 요청 해제). */
    static int flags(boolean prevent, boolean keepDisplayOn) {
        if (!prevent) {
            return ES_CONTINUOUS;
        }
        return keepDisplayOn
                ? ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED
                : ES_CONTINUOUS | ES_SYSTEM_REQUIRED;
    }

    /** @param releaseReason 풀 때 로그에 적는 까닭 — 장외 대기 또는 앱 종료(2026-10-15: 장중 종료가 "장외 대기"로 찍혔다) */
    private void apply(boolean prevent, String releaseReason) {
        if (Kernel32.INSTANCE.SetThreadExecutionState(flags(prevent, keepDisplayOn)) == 0) {
            log.warn("SetThreadExecutionState 실패 — Windows 절전 {}에 실패했다", prevent ? "방지" : "방지 해제");
            return;
        }
        if (!prevent) {
            log.info("Windows 유휴 절전 방지 해제 — {}", releaseReason);
        } else if (keepDisplayOn) {
            log.info("Windows 유휴 절전 방지 켬(화면 켜 둠 — 최신 대기 모드 진입 차단) — 장 대응 시간(ACTIVE, 거래일 장전 준비 포함)");
        } else {
            log.info("Windows 유휴 절전 방지 켬 — 장 대응 시간(ACTIVE, 거래일 장전 준비 포함)");
        }
    }

    @PreDestroy
    void shutdown() {
        if (windows && Boolean.TRUE.equals(requested)) {
            owner.execute(() -> apply(false, "앱 종료"));
        }
        owner.shutdown();
    }
}

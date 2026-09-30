package com.autostock.monitor;

import com.sun.jna.platform.win32.Kernel32;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Windows 유휴 절전 차단 — {@code SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED)}.
 *
 * <p>이 요청은 호출한 스레드에 붙고 그 스레드가 끝나면 사라진다. 그래서 스케줄러 풀이 아니라 앱과 수명을 같이 하는
 * 전용 스레드 하나에서만 호출한다. Windows가 아니면(테스트·CI) 아무것도 하지 않는다.
 *
 * <p>막는 것은 <b>유휴 절전</b>뿐이다 — 사용자가 직접 절전을 누르거나 노트북 덮개를 닫는 것은 막지 못한다.
 * 동작 확인: 장중에 관리자 PowerShell에서 {@code powercfg /requests}를 실행하면 SYSTEM 항목에 java.exe가 보여야 한다.
 */
@Component
public class SystemSleepBlocker {

    private static final Logger log = LoggerFactory.getLogger(SystemSleepBlocker.class);

    private static final int ES_CONTINUOUS = 0x80000000;
    private static final int ES_SYSTEM_REQUIRED = 0x00000001;

    private final boolean windows =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    private final ExecutorService owner = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "sleep-blocker");
        thread.setDaemon(true);
        return thread;
    });

    /** 마지막으로 요청한 상태 — 같은 요청을 30초마다 반복하지 않기 위한 것. null이면 아직 요청 전. */
    private volatile Boolean requested;

    /** true면 유휴 절전을 막고, false면 막던 것을 푼다. 상태가 바뀔 때만 실제로 호출한다. */
    public void preventSleep(boolean prevent) {
        if (!windows || Boolean.valueOf(prevent).equals(requested)) {
            return;
        }
        requested = prevent;
        owner.execute(() -> apply(prevent));
    }

    private void apply(boolean prevent) {
        int flags = prevent ? ES_CONTINUOUS | ES_SYSTEM_REQUIRED : ES_CONTINUOUS;
        if (Kernel32.INSTANCE.SetThreadExecutionState(flags) == 0) {
            log.warn("SetThreadExecutionState 실패 — Windows 절전 {}에 실패했다", prevent ? "방지" : "방지 해제");
            return;
        }
        log.info(prevent ? "Windows 유휴 절전 방지 켬 — 장 대응 시간(ACTIVE)"
                : "Windows 유휴 절전 방지 해제 — 장외 대기(STANDBY)");
    }

    @PreDestroy
    void shutdown() {
        if (windows && Boolean.TRUE.equals(requested)) {
            owner.execute(() -> apply(false));
        }
        owner.shutdown();
    }
}

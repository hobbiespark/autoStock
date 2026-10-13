package com.autostock.monitor;

import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * Windows {@code GetSystemPowerStatus}로 충전기 연결·배터리 잔량을 읽는다. Windows가 아니면(테스트·CI) 늘 빈 값이다.
 *
 * <p>jna-platform 5.14의 Kernel32에는 이 함수가 없어 필요한 만큼만 직접 선언한다. 조회가 실패하면 경고를 한 번만 남기고
 * 빈 값을 돌려준다 — 전원 감시는 부가 기능이라 절전 대응을 막으면 안 된다.
 */
@Component
public class WindowsPowerSource implements PowerSource {

    private static final Logger log = LoggerFactory.getLogger(WindowsPowerSource.class);

    /** {@code ACLineStatus} — 0 배터리, 1 충전기, 255 모름. */
    static final int AC_OFFLINE = 0;
    static final int AC_ONLINE = 1;
    /** {@code BatteryFlag} 128 — 배터리 없음(데스크톱). */
    static final int NO_SYSTEM_BATTERY = 128;
    /** {@code BatteryLifePercent}·플래그의 "모름". */
    static final int UNKNOWN = 255;

    private final boolean windows =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    private volatile boolean warned;

    @Override
    public Optional<PowerStatus> read() {
        if (!windows) {
            return Optional.empty();
        }
        try {
            SystemPowerStatus status = new SystemPowerStatus();
            if (!Kernel32Power.INSTANCE.GetSystemPowerStatus(status)) {
                warnOnce("GetSystemPowerStatus가 실패를 돌려줬다");
                return Optional.empty();
            }
            return toStatus(status.ACLineStatus & 0xFF, status.BatteryFlag & 0xFF, status.BatteryLifePercent & 0xFF);
        } catch (LinkageError | RuntimeException e) {
            warnOnce(e.getClass().getSimpleName() + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Win32 값 → 전원 상태. 충전기 연결을 모르면 빈 값, 배터리가 없거나 잔량을 모르면 잔량만 null. */
    static Optional<PowerStatus> toStatus(int acLineStatus, int batteryFlag, int batteryLifePercent) {
        if (acLineStatus != AC_OFFLINE && acLineStatus != AC_ONLINE) {
            return Optional.empty();
        }
        boolean noBattery = batteryFlag != UNKNOWN && (batteryFlag & NO_SYSTEM_BATTERY) != 0;
        Integer percent = noBattery || batteryLifePercent > 100 ? null : batteryLifePercent;
        return Optional.of(new PowerStatus(acLineStatus == AC_ONLINE, percent));
    }

    private void warnOnce(String reason) {
        if (!warned) {
            warned = true;
            log.warn("전원 상태 조회 실패 — 배터리 감시를 건너뛴다(앱 동작에는 영향 없음): {}", reason);
        }
    }

    /** {@code kernel32!GetSystemPowerStatus} — 처음 쓸 때(Windows에서만) 읽어 들인다. */
    interface Kernel32Power extends StdCallLibrary {
        Kernel32Power INSTANCE = Native.load("kernel32", Kernel32Power.class);

        boolean GetSystemPowerStatus(SystemPowerStatus status);
    }

    /** Win32 {@code SYSTEM_POWER_STATUS} — 필드 이름·순서는 Win32 그대로다(JNA 구조체 규칙). */
    @Structure.FieldOrder({"ACLineStatus", "BatteryFlag", "BatteryLifePercent", "SystemStatusFlag",
            "BatteryLifeTime", "BatteryFullLifeTime"})
    public static class SystemPowerStatus extends Structure {
        public byte ACLineStatus;
        public byte BatteryFlag;
        public byte BatteryLifePercent;
        public byte SystemStatusFlag;
        public int BatteryLifeTime;
        public int BatteryFullLifeTime;
    }
}

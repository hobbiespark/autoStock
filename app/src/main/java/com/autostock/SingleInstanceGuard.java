package com.autostock;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 같은 PC에서 앱이 두 번 뜨지 않게 하는 잠금(2026-10-15 로그 점검).
 *
 * <p>10/15 07:53 실측: 17초 간격으로 두 번 기동됐다. 두 번째(PID 25964)는 Flyway 검증·JPA·종목명 사전까지 올린 뒤 8080 포트
 * 충돌로 죽었다. 포트가 우연히 막아 준 것이다 — 두 번째가 먼저 웹 서버를 띄웠거나 포트가 달랐다면 두 앱이 같은 계좌로 매매·실측·
 * 알림을 했을 것이다. 배치 파일에서 미리 막는 것(scripts/start_autostock.bat의 8080 확인)은 둘이 거의 동시에 시작하면 소용없다.
 *
 * <p>Spring을 올리기 전에 잠금 파일({@value #LOCK_FILE}, 작업 폴더 기준 — bootRun은 {@code app/}이라 {@code app/logs/})에 OS 파일
 * 잠금을 건다. 다른 프로세스가 이미 잡고 있으면 실행을 멈춘다. 잠금은 프로세스가 끝나면(비정상 종료 포함) OS가 푼다 — 남은 파일을
 * 지울 필요가 없다.
 *
 * <p>끄기: {@code --autostock.single-instance=false} 인자, 또는 같은 이름의 시스템 속성.
 */
final class SingleInstanceGuard {

    static final String LOCK_FILE = "logs/autostock.instance.lock";
    static final String DISABLE_KEY = "autostock.single-instance";

    /** 프로세스가 끝날 때까지 쥐고 있는다 — 지역 변수면 GC가 채널을 닫아 잠금이 풀린다. */
    private static FileChannel channel;
    private static FileLock lock;

    private SingleInstanceGuard() {
    }

    static boolean disabled(String[] args) {
        if ("false".equalsIgnoreCase(System.getProperty(DISABLE_KEY))) {
            return true;
        }
        if (args != null) {
            for (String arg : args) {
                if (("--" + DISABLE_KEY + "=false").equalsIgnoreCase(arg.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @return 잠금을 잡았으면 true, 다른 프로세스(또는 이 프로세스의 다른 잠금)가 이미 잡고 있으면 false. 잠금 파일 자체를 열 수
     *         없으면 경고만 하고 true — 잠금 파일 문제로 앱이 못 뜨는 편이 더 나쁘다.
     */
    static synchronized boolean acquire(Path file) {
        if (lock != null && lock.isValid()) {
            return true;
        }
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            FileChannel opened = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock acquired;
            try {
                acquired = opened.tryLock();
            } catch (OverlappingFileLockException e) {
                acquired = null;
            }
            if (acquired == null) {
                opened.close();
                return false;
            }
            channel = opened;
            lock = acquired;
            return true;
        } catch (IOException e) {
            System.err.println("[!] 단일 실행 잠금 파일을 열지 못했다 — 잠금 없이 계속: " + file.toAbsolutePath() + " — "
                    + e.getMessage());
            return true;
        }
    }

    /** 시험용 — 잡은 잠금을 푼다. */
    static synchronized void release() {
        try {
            if (lock != null) {
                lock.release();
            }
            if (channel != null) {
                channel.close();
            }
        } catch (IOException ignored) {
            // 시험 정리용 — 무시
        } finally {
            lock = null;
            channel = null;
        }
    }
}

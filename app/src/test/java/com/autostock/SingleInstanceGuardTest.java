package com.autostock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 단일 실행 잠금(2026-10-15 07:53 이중 기동) — 잠금이 잡혀 있으면 두 번째 실행을 멈추고, 풀리면 잡는다.
 */
class SingleInstanceGuardTest {

    @TempDir
    Path dir;

    @AfterEach
    void tearDown() {
        SingleInstanceGuard.release();
    }

    @Test
    void 다른_실행이_잠금을_잡고_있으면_잡지_못하고_풀리면_잡는다() throws Exception {
        Path file = dir.resolve("logs").resolve("autostock.instance.lock");
        Files.createDirectories(file.getParent());
        try (FileChannel other = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock held = other.lock()) {
            assertTrue(held.isValid());
            assertFalse(SingleInstanceGuard.acquire(file), "먼저 뜬 실행이 잡고 있다");
        }
        assertTrue(SingleInstanceGuard.acquire(file), "먼저 뜬 실행이 끝나면 잡는다");
    }

    @Test
    void 잠금_폴더가_없으면_만들어서_잡는다() {
        assertTrue(SingleInstanceGuard.acquire(dir.resolve("new").resolve("autostock.instance.lock")));
        assertTrue(Files.exists(dir.resolve("new").resolve("autostock.instance.lock")));
    }

    @Test
    void 인자로_끌_수_있다() {
        assertTrue(SingleInstanceGuard.disabled(new String[]{"--execution.mode=LIVE", "--autostock.single-instance=false"}));
        assertFalse(SingleInstanceGuard.disabled(new String[]{"--execution.mode=LIVE"}));
        assertFalse(SingleInstanceGuard.disabled(null));
    }
}

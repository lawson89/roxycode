package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.lang3.SystemUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.service.ProcessRunner.ProcessResult;

class ProcessRunnerTest {

    @TempDir
    Path tempDir;

    private static List<String> shellCmd(String script) {
        if (SystemUtils.IS_OS_WINDOWS) {
            return List.of("cmd.exe", "/c", script);
        }
        return List.of("sh", "-c", script);
    }

    @Test
    void testSuccessfulExecutionAndEnv() throws IOException {
        String script = SystemUtils.IS_OS_WINDOWS ? "echo %TEST_VAR%" : "echo $TEST_VAR";
        ProcessResult result = ProcessRunner.run(shellCmd(script), tempDir, Map.of("TEST_VAR", "hello_roxy"),
                Duration.ofSeconds(5));

        assertFalse(result.timedOut());
        assertEquals(0, result.exitCode());
        assertTrue(result.output().trim().contains("hello_roxy"));
    }

    @Test
    void testSeparateStdoutAndStderrDraining() throws IOException {
        String script = "echo out_msg && echo err_msg >&2";
        ProcessResult result = ProcessRunner.run(shellCmd(script), tempDir, false);

        assertFalse(result.timedOut());
        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("out_msg"));
        assertFalse(result.output().contains("err_msg"));
        assertTrue(result.errorOutput().contains("err_msg"));
    }

    @Test
    void testTimeoutDestroysProcessTree() throws IOException {
        ProcessResult result = ProcessRunner.run(shellCmd("sleep 10"), tempDir, Map.of(), Duration.ofMillis(200), true);

        assertTrue(result.timedOut());
        assertEquals(-1, result.exitCode());
    }

    @Test
    void testInterruptRestoresFlagAndThrowsCancellationException() throws InterruptedException {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Boolean> interruptRestored = new AtomicReference<>(false);

        Thread worker = new Thread(() -> {
            try {
                ProcessRunner.run(shellCmd("sleep 10"), tempDir, Map.of(), Duration.ofSeconds(10), true);
            } catch (Throwable t) {
                thrown.set(t);
                interruptRestored.set(Thread.currentThread().isInterrupted());
            }
        });

        worker.start();
        Thread.sleep(150);
        worker.interrupt();
        worker.join(5000);

        assertFalse(worker.isAlive(), "Worker thread should have terminated promptly");
        assertInstanceOf(CancellationException.class, thrown.get());
        assertTrue(interruptRestored.get(), "Thread interrupt flag must be restored");
    }

    @Test
    void testStreamReaderExecutorUsesVirtualThreads() throws Exception {
        boolean isVirtual = java.util.concurrent.CompletableFuture
                .supplyAsync(() -> Thread.currentThread().isVirtual(), ProcessRunner.getStreamReaderExecutor()).get();
        assertTrue(isVirtual, "ProcessRunner stream reader executor must run on virtual threads");
    }

    @Test
    void testReadStreamNormalAndBoundedTruncationDrainsRemainingStream() {
        byte[] normalBytes = "hello world\nline two".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder normalBuf = new StringBuilder();
        ProcessRunner.readStream(new java.io.ByteArrayInputStream(normalBytes), normalBuf, 100);
        assertEquals("hello world\nline two", normalBuf.toString());

        StringBuilder exactBuf = new StringBuilder();
        ProcessRunner.readStream(
                new java.io.ByteArrayInputStream("12345".getBytes(java.nio.charset.StandardCharsets.UTF_8)), exactBuf,
                5);
        assertEquals("12345", exactBuf.toString());

        AtomicBoolean closed = new AtomicBoolean(false);
        int totalBytes = 32_768;
        java.io.InputStream largeStream = new java.io.InputStream() {
            private int remaining = totalBytes;

            @Override
            public int read() {
                if (remaining <= 0) {
                    return -1;
                }
                remaining--;
                return 'A';
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (remaining <= 0) {
                    return -1;
                }
                int toProvide = Math.min(len, remaining);
                java.util.Arrays.fill(b, off, off + toProvide, (byte) 'A');
                remaining -= toProvide;
                return toProvide;
            }

            @Override
            public void close() {
                closed.set(true);
            }

            int getRemaining() {
                return remaining;
            }
        };

        StringBuilder truncatedBuf = new StringBuilder();
        int cap = 100;
        ProcessRunner.readStream(largeStream, truncatedBuf, cap);
        assertEquals("A".repeat(cap) + ProcessRunner.TRUNCATION_SUFFIX, truncatedBuf.toString());
        assertTrue(closed.get(), "Stream must be closed after draining");
        assertEquals(-1, assertDoesNotThrow(() -> largeStream.read()), "Entire stream must be drained to EOF");
    }
}

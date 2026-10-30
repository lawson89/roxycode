package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.lang3.SystemUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.service.ProcessRunner.BoundedOutputStream;
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
    void testSuccessfulExecutionEnvAndProcessResultHelpers() {
        String script;
        if (SystemUtils.IS_OS_WINDOWS) {
            script = "echo %TEST_VAR%";
        } else {
            script = "echo $TEST_VAR";
        }
        ProcessResult result = ProcessRunner.run(shellCmd(script), tempDir, Map.of("TEST_VAR", "hello_roxy"),
                Duration.ofSeconds(5), true, null);

        assertTrue(result.isSuccess());
        assertFalse(result.timedOut());
        assertEquals(0, result.exitCode());
        assertTrue(result.output().trim().contains("hello_roxy"));
        assertEquals(List.of("hello_roxy"), result.outputLines().stream().map(String::trim).toList());

        ProcessResult emptyRes = new ProcessResult(0, "", "", false);
        assertTrue(emptyRes.isSuccess());
        assertTrue(emptyRes.outputLines().isEmpty());

        ProcessResult failedRes = ProcessRunner.run(List.of("non_existent_command_xyz_12345"));
        assertFalse(failedRes.isSuccess());
        assertFalse(failedRes.timedOut());
        assertEquals(-1, failedRes.exitCode());
        assertFalse(failedRes.errorOutput().isBlank());
    }

    @Test
    void testSeparateStdoutAndStderrDraining() {
        String script = "echo out_msg && echo err_msg >&2";
        ProcessResult result = ProcessRunner.run(shellCmd(script), tempDir, false);

        assertTrue(result.isSuccess());
        assertFalse(result.timedOut());
        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("out_msg"));
        assertFalse(result.output().contains("err_msg"));
        assertTrue(result.errorOutput().contains("err_msg"));
    }

    @Test
    void testTimeoutDestroysProcessTree() {
        ProcessResult result = ProcessRunner.run(shellCmd("sleep 10"), tempDir, Map.of(), Duration.ofMillis(200), true,
                null);

        assertFalse(result.isSuccess());
        assertTrue(result.timedOut());
        assertEquals(-1, result.exitCode());
    }

    @Test
    void testInterruptRestoresFlagAndThrowsCancellationException() throws InterruptedException {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Boolean> interruptRestored = new AtomicReference<>(false);

        Thread worker = new Thread(() -> {
            try {
                ProcessRunner.run(shellCmd("sleep 10"), tempDir, Map.of(), Duration.ofSeconds(10), true, null);
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
    void testBoundedOutputStreamNormalAndTruncationDiscardsExcessBytes() throws IOException {
        byte[] normalBytes = "hello world\nline two".getBytes(StandardCharsets.UTF_8);
        BoundedOutputStream normalOut = new BoundedOutputStream(100);
        normalOut.write(normalBytes);
        assertEquals("hello world\nline two", normalOut.toString());
        BoundedOutputStream exactOut = new BoundedOutputStream(5);
        exactOut.write("12345".getBytes(StandardCharsets.UTF_8));
        assertEquals("12345", exactOut.toString());

        int cap = 100;
        BoundedOutputStream truncatedOut = new BoundedOutputStream(cap);
        byte[] chunk = new byte[8192];
        Arrays.fill(chunk, (byte) 'A');
        for (int i = 0; i < 4; i++) {
            truncatedOut.write(chunk, 0, chunk.length);
        }
        truncatedOut.write('B');
        assertEquals("A".repeat(cap) + ProcessRunner.TRUNCATION_SUFFIX, truncatedOut.toString());

        BoundedOutputStream singleByteOut = new BoundedOutputStream(3);
        for (char c : "abcdef".toCharArray()) {
            singleByteOut.write(c);
        }
        assertEquals("abc" + ProcessRunner.TRUNCATION_SUFFIX, singleByteOut.toString());
    }
}

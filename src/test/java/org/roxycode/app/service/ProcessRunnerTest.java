package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.service.ProcessRunner.ProcessResult;

class ProcessRunnerTest {

  @TempDir Path tempDir;

  private static List<String> shellCmd(String script) {
    if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
      return List.of("cmd.exe", "/c", script);
    }
    return List.of("sh", "-c", script);
  }

  @Test
  void testSuccessfulExecutionAndEnv() throws IOException {
    String script =
        System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
            ? "echo %TEST_VAR%"
            : "echo $TEST_VAR";
    ProcessResult result =
        ProcessRunner.run(
            shellCmd(script), tempDir, Map.of("TEST_VAR", "hello_roxy"), Duration.ofSeconds(5));

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
    ProcessResult result =
        ProcessRunner.run(
            shellCmd("sleep 10"), tempDir, Map.of(), Duration.ofMillis(200), true);

    assertTrue(result.timedOut());
    assertEquals(-1, result.exitCode());
  }

  @Test
  void testInterruptRestoresFlagAndThrowsCancellationException() throws InterruptedException {
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    AtomicReference<Boolean> interruptRestored = new AtomicReference<>(false);

    Thread worker =
        new Thread(
            () -> {
              try {
                ProcessRunner.run(
                    shellCmd("sleep 10"), tempDir, Map.of(), Duration.ofSeconds(10), true);
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
}

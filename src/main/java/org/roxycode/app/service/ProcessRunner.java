package org.roxycode.app.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Shared utility for safe external process execution. Prevents stderr buffer deadlocks, enforces
 * timeouts, closes stdin, cleans up descendant processes on timeout or interruption, and preserves
 * thread interruption status.
 */
@Component
public class ProcessRunner {
  private static final Logger log = LoggerFactory.getLogger(ProcessRunner.class);
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  /** Result of executing an external process. */
  public record ProcessResult(
      int exitCode, String output, String errorOutput, boolean timedOut) {
    public ProcessResult(int exitCode, String output, boolean timedOut) {
      this(exitCode, output, "", timedOut);
    }
  }

  public static ProcessResult run(List<String> command, Path workingDir) throws IOException {
    return run(command, workingDir, Map.of(), DEFAULT_TIMEOUT, true);
  }

  public static ProcessResult run(
      List<String> command, Path workingDir, boolean redirectErrorStream) throws IOException {
    return run(command, workingDir, Map.of(), DEFAULT_TIMEOUT, redirectErrorStream);
  }

  public static ProcessResult run(
      List<String> command, Path workingDir, Map<String, String> env) throws IOException {
    return run(command, workingDir, env, DEFAULT_TIMEOUT, true);
  }

  public static ProcessResult run(
      List<String> command, Path workingDir, Map<String, String> env, Duration timeout)
      throws IOException {
    return run(command, workingDir, env, timeout, true);
  }

  public static ProcessResult run(
      List<String> command,
      Path workingDir,
      Map<String, String> env,
      Duration timeout,
      boolean redirectErrorStream)
      throws IOException {
    ProcessBuilder pb = new ProcessBuilder(command);
    if (workingDir != null) {
      pb.directory(workingDir.toFile());
    }
    if (env != null && !env.isEmpty()) {
      pb.environment().putAll(env);
    }
        pb.redirectErrorStream(redirectErrorStream);

    Process process = pb.start();
    try {
      process.getOutputStream().close();
    } catch (IOException ignored) {
    }
    StringBuffer stdoutBuffer = new StringBuffer();
    StringBuffer stderrBuffer = new StringBuffer();

    CompletableFuture<Void> stdoutFuture =
        CompletableFuture.runAsync(() -> readStream(process.getInputStream(), stdoutBuffer));
    CompletableFuture<Void> stderrFuture =
        redirectErrorStream
            ? CompletableFuture.completedFuture(null)
            : CompletableFuture.runAsync(() -> readStream(process.getErrorStream(), stderrBuffer));

    boolean cleanupNeeded = true;
    try {
      Duration effectiveTimeout = timeout != null ? timeout : DEFAULT_TIMEOUT;
      boolean finished = process.waitFor(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        destroyProcessTree(process);
        cleanupNeeded = false;
        awaitReaders(stdoutFuture, stderrFuture, 2, TimeUnit.SECONDS);
        return new ProcessResult(-1, stdoutBuffer.toString(), stderrBuffer.toString(), true);
      }

      int exitCode = process.exitValue();
      cleanupNeeded = false;
      awaitReaders(stdoutFuture, stderrFuture, 5, TimeUnit.SECONDS);
      return new ProcessResult(exitCode, stdoutBuffer.toString(), stderrBuffer.toString(), false);
    } catch (InterruptedException e) {
      destroyProcessTree(process);
      cleanupNeeded = false;
      Thread.currentThread().interrupt();
      CancellationException ce = new CancellationException("Process execution interrupted");
      ce.initCause(e);
      throw ce;
    } finally {
      if (cleanupNeeded || process.isAlive()) {
        destroyProcessTree(process);
      }
    }
  }

  private static void readStream(InputStream inputStream, StringBuffer buffer) {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
      String line = reader.readLine();
      while (line != null) {
        buffer.append(line).append('\n');
        line = reader.readLine();
      }
    } catch (IOException e) {
      log.debug("Stream closed or failed while reading process output", e);
    }
  }

  private static void awaitReaders(
      CompletableFuture<Void> stdoutFuture,
      CompletableFuture<Void> stderrFuture,
      long timeout,
      TimeUnit unit) {
    try {
      CompletableFuture.allOf(stdoutFuture, stderrFuture).get(timeout, unit);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      log.debug("Output readers did not complete within {} {}", timeout, unit, e);
    }
  }

  public static void destroyProcessTree(Process process) {
    if (process == null) {
      return;
    }
    try {
      process
          .descendants()
          .forEach(
              handle -> {
                try {
                  handle.destroyForcibly();
                } catch (Exception ignored) {
                }
              });
    } catch (Exception ignored) {
    }
    try {
      process.destroyForcibly();
    } catch (Exception ignored) {
    }
  }
}

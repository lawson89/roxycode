package org.roxycode.app.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ProcessRunner {
    private static final Logger log = LoggerFactory.getLogger(ProcessRunner.class);
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final ExecutorService STREAM_READER_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private ProcessRunner() {
    }

    public record ProcessResult(int exitCode, String output, String errorOutput, boolean timedOut) {
    }

    public static ProcessResult run(List<String> command, Path workingDir, boolean redirectErrorStream)
            throws IOException {
        return run(command, workingDir, Map.of(), DEFAULT_TIMEOUT, redirectErrorStream);
    }

    public static ProcessResult run(List<String> command, Path workingDir, Map<String, String> env) throws IOException {
        return run(command, workingDir, env, DEFAULT_TIMEOUT, true);
    }

    public static ProcessResult run(List<String> command, Path workingDir, Map<String, String> env, Duration timeout)
            throws IOException {
        return run(command, workingDir, env, timeout, true);
    }

    public static ProcessResult run(List<String> command, Path workingDir, Map<String, String> env, Duration timeout,
            boolean redirectErrorStream) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null)
            pb.directory(workingDir.toFile());
        if (env != null && !env.isEmpty())
            pb.environment().putAll(env);
        pb.redirectErrorStream(redirectErrorStream);

        Process process = pb.start();
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
        }
        StringBuilder stdoutBuffer = new StringBuilder();
        StringBuilder stderrBuffer = new StringBuilder();

        CompletableFuture<Void> stdoutFuture = CompletableFuture
                .runAsync(() -> readStream(process.getInputStream(), stdoutBuffer), STREAM_READER_EXECUTOR);
        CompletableFuture<Void> stderrFuture = redirectErrorStream
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.runAsync(() -> readStream(process.getErrorStream(), stderrBuffer),
                        STREAM_READER_EXECUTOR);

        boolean cleanupNeeded = true;
        try {
            Duration effectiveTimeout = timeout != null ? timeout : DEFAULT_TIMEOUT;
            if (!process.waitFor(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
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
            if (cleanupNeeded || process.isAlive())
                destroyProcessTree(process);
        }
    }

    static ExecutorService getStreamReaderExecutor() {
        return STREAM_READER_EXECUTOR;
    }

    private static void readStream(InputStream inputStream, StringBuilder buffer) {
        try (inputStream) {
            buffer.append(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.debug("Stream closed or failed while reading process output", e);
        }
    }

    private static void awaitReaders(CompletableFuture<Void> stdoutFuture, CompletableFuture<Void> stderrFuture,
            long timeout, TimeUnit unit) {
        try {
            CompletableFuture.allOf(stdoutFuture, stderrFuture).get(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Output readers did not complete within {} {}", timeout, unit, e);
        }
    }

    public static void destroyProcessTree(Process process) {
        if (process == null)
            return;
        try {
            process.descendants().forEach(handle -> {
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

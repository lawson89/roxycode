package org.roxycode.app.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.io.output.ThresholdingOutputStream;
import org.zeroturnaround.exec.InvalidExitValueException;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessInitException;

public final class ProcessRunner {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    static final int MAX_OUTPUT_CHARS = 2 * 1024 * 1024;
    static final String TRUNCATION_SUFFIX = "\n... [Output truncated]";

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

    @SuppressWarnings("PMD.PreserveStackTrace")
    public static ProcessResult run(List<String> command, Path workingDir, Map<String, String> env, Duration timeout,
            boolean redirectErrorStream) throws IOException {
        BoundedOutputStream stdoutStream = new BoundedOutputStream();
        BoundedOutputStream stderrStream = new BoundedOutputStream();
        Duration effectiveTimeout = timeout != null ? timeout : DEFAULT_TIMEOUT;

        ProcessExecutor executor = new ProcessExecutor().command(command).redirectErrorStream(redirectErrorStream)
                .redirectOutput(stdoutStream).timeout(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .stopper(ProcessRunner::destroyProcessTree).destroyOnExit();

        if (!redirectErrorStream) {
            executor.redirectError(stderrStream);
        }
        if (workingDir != null) {
            executor.directory(workingDir.toFile());
        }
        if (env != null && !env.isEmpty()) {
            executor.environment(env);
        }

        try {
            org.zeroturnaround.exec.ProcessResult ztResult = executor.execute();
            return new ProcessResult(ztResult.getExitValue(), stdoutStream.toString(), stderrStream.toString(), false);
        } catch (TimeoutException e) {
            return new ProcessResult(-1, stdoutStream.toString(), stderrStream.toString(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            CancellationException ce = new CancellationException("Process execution interrupted");
            ce.initCause(e);
            throw ce;
        } catch (ProcessInitException e) {
            if (e.getCause() instanceof IOException ioEx) {
                throw ioEx;
            }
            throw e;
        } catch (InvalidExitValueException e) {
            throw new IOException("Process execution failed", e);
        }
    }

    static final class BoundedOutputStream extends ThresholdingOutputStream {

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private OutputStream currentStream = buffer;

        BoundedOutputStream() {
            this(MAX_OUTPUT_CHARS);
        }

        BoundedOutputStream(int maxBytes) {
            super(maxBytes);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            long remaining = getThreshold() - getByteCount();
            if (remaining > 0 && len > remaining) {
                int prefixLen = (int) remaining;
                super.write(b, off, prefixLen);
                off += prefixLen;
                len -= prefixLen;
            }
            super.write(b, off, len);
        }

        @Override
        protected OutputStream getStream() {
            return currentStream;
        }

        @Override
        protected void thresholdReached() {
            buffer.writeBytes(TRUNCATION_SUFFIX.getBytes(StandardCharsets.UTF_8));
            currentStream = NullOutputStream.INSTANCE;
        }

        @Override
        public String toString() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    public static void destroyProcessTree(Process process) {
        if (process == null) {
            return;
        }
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

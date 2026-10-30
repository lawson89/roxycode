package org.roxycode.app.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.io.output.TeeOutputStream;
import org.apache.commons.io.output.ThresholdingOutputStream;

public final class ProcessRunner {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    static final int MAX_OUTPUT_CHARS = 2 * 1024 * 1024;
    static final String TRUNCATION_SUFFIX = "\n... [Output truncated]";

    private ProcessRunner() {
    }

    public record ProcessResult(int exitCode, String output, String errorOutput, boolean timedOut) {

        public boolean isSuccess() {
            return !timedOut && exitCode == 0;
        }

        public List<String> outputLines() {
            if (output == null || output.isEmpty()) {
                return List.of();
            }
            return output.lines().toList();
        }
    }

    public static ProcessResult run(List<String> command) {
        return run(command, null, Map.of(), DEFAULT_TIMEOUT, true, null);
    }

    public static ProcessResult run(List<String> command, Path workingDir, boolean redirectErrorStream) {
        return run(command, workingDir, Map.of(), DEFAULT_TIMEOUT, redirectErrorStream, null);
    }

    public static ProcessResult run(List<String> command, Path workingDir, Map<String, String> env) {
        return run(command, workingDir, env, DEFAULT_TIMEOUT, true, null);
    }

    @SuppressWarnings({"PMD.CloseResource", "PMD.PreserveStackTrace"})
    public static ProcessResult run(List<String> command, Path workingDir, Map<String, String> env, Duration timeout,
            boolean redirectErrorStream, OutputStream outputMirror) {
        BoundedOutputStream stdoutStream = new BoundedOutputStream(MAX_OUTPUT_CHARS);
        BoundedOutputStream stderrStream = new BoundedOutputStream(MAX_OUTPUT_CHARS);
        OutputStream primaryOut = stdoutStream;
        if (outputMirror != null) {
            primaryOut = new TeeOutputStream(stdoutStream, outputMirror);
        }
        Duration effectiveTimeout = DEFAULT_TIMEOUT;
        if (timeout != null) {
            effectiveTimeout = timeout;
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(redirectErrorStream);
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        if (env != null && !env.isEmpty()) {
            pb.environment().putAll(env);
        }

        Process process = null;
        try {
            process = pb.start();
            OutputStream targetOut = primaryOut;
            Process startedProcess = process;
            Thread stdoutThread = Thread.ofVirtual()
                    .start(() -> drainStream(startedProcess.getInputStream(), targetOut));
            Thread stderrThread = null;
            if (!redirectErrorStream) {
                stderrThread = Thread.ofVirtual()
                        .start(() -> drainStream(startedProcess.getErrorStream(), stderrStream));
            }

            boolean completed = process.waitFor(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                destroyProcessTree(process);
                stdoutThread.join();
                if (stderrThread != null) {
                    stderrThread.join();
                }
                return new ProcessResult(-1, stdoutStream.toString(), stderrStream.toString(), true);
            }

            stdoutThread.join();
            if (stderrThread != null) {
                stderrThread.join();
            }
            return new ProcessResult(process.exitValue(), stdoutStream.toString(), stderrStream.toString(), false);
        } catch (InterruptedException e) {
            destroyProcessTree(process);
            Thread.currentThread().interrupt();
            CancellationException ce = new CancellationException("Process execution interrupted");
            ce.initCause(e);
            throw ce;
        } catch (IOException e) {
            destroyProcessTree(process);
            String errorMsg;
            if (e.getMessage() != null) {
                errorMsg = e.getMessage();
            } else {
                errorMsg = e.getClass().getSimpleName();
            }
            return new ProcessResult(-1, "", errorMsg, false);
        }
    }

    private static void drainStream(InputStream source, OutputStream target) {
        try (InputStream in = source) {
            in.transferTo(target);
        } catch (IOException ignored) {
        }
    }

    static final class BoundedOutputStream extends ThresholdingOutputStream {

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private OutputStream currentStream = buffer;

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

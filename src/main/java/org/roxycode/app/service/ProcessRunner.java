package org.roxycode.app.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.StartedProcess;
import org.zeroturnaround.exec.listener.ProcessListener;
import org.zeroturnaround.exec.stream.ExecuteStreamHandler;

public final class ProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessRunner.class);
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    static final int MAX_OUTPUT_CHARS = 2 * 1024 * 1024;
    static final String TRUNCATION_SUFFIX = "\n... [Output truncated]";
    private static final int READ_CHUNK_SIZE = 8192;
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

    @SuppressWarnings("PMD.PreserveStackTrace")
    public static ProcessResult run(List<String> command, Path workingDir, Map<String, String> env, Duration timeout,
            boolean redirectErrorStream) throws IOException {
        StringBuilder stdoutBuffer = new StringBuilder();
        StringBuilder stderrBuffer = new StringBuilder();
        AtomicReference<Process> startedProcessRef = new AtomicReference<>();

        ProcessExecutor executor = new ProcessExecutor().command(command).redirectErrorStream(redirectErrorStream)
                .destroyOnExit()
                .streams(new BoundedExecuteStreamHandler(stdoutBuffer, stderrBuffer, redirectErrorStream))
                .addListener(new ProcessListener() {
                    @Override
                    public void afterStart(Process process, ProcessExecutor procExecutor) {
                        startedProcessRef.set(process);
                    }
                });

        if (workingDir != null) {
            executor.directory(workingDir.toFile());
        }
        if (env != null && !env.isEmpty()) {
            executor.environment(env);
        }

        Duration effectiveTimeout = timeout != null ? timeout : DEFAULT_TIMEOUT;
        StartedProcess started = executor.start();
        Process rawProcess = started.getProcess();
        startedProcessRef.compareAndSet(null, rawProcess);

        boolean cleanupNeeded = true;
        try {
            org.zeroturnaround.exec.ProcessResult ztResult = started.getFuture().get(effectiveTimeout.toMillis(),
                    TimeUnit.MILLISECONDS);
            cleanupNeeded = false;
            return new ProcessResult(ztResult.getExitValue(), stdoutBuffer.toString(), stderrBuffer.toString(), false);
        } catch (TimeoutException e) {
            destroyProcessTree(rawProcess);
            started.getFuture().cancel(true);
            cleanupNeeded = false;
            return new ProcessResult(-1, stdoutBuffer.toString(), stderrBuffer.toString(), true);
        } catch (InterruptedException e) {
            destroyProcessTree(rawProcess);
            started.getFuture().cancel(true);
            cleanupNeeded = false;
            Thread.currentThread().interrupt();
            CancellationException ce = new CancellationException("Process execution interrupted");
            ce.initCause(e);
            throw ce;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioEx) {
                throw ioEx;
            }
            throw new IOException("Process execution failed", cause != null ? cause : e);
        } finally {
            if (cleanupNeeded || (rawProcess != null && rawProcess.isAlive())) {
                destroyProcessTree(rawProcess);
            }
        }
    }

    @SuppressWarnings("PMD.AvoidStringBufferField")
    private static final class BoundedExecuteStreamHandler implements ExecuteStreamHandler {

        private final StringBuilder stdoutBuffer;
        private final StringBuilder stderrBuffer;
        private final boolean redirectErrorStream;
        private InputStream stdoutStream;
        private InputStream stderrStream;
        private OutputStream stdinStream;
        private java.util.concurrent.CompletableFuture<Void> stdoutFuture;
        private java.util.concurrent.CompletableFuture<Void> stderrFuture;

        BoundedExecuteStreamHandler(StringBuilder stdoutBuffer, StringBuilder stderrBuffer,
                boolean redirectErrorStream) {
            this.stdoutBuffer = stdoutBuffer;
            this.stderrBuffer = stderrBuffer;
            this.redirectErrorStream = redirectErrorStream;
        }

        @Override
        public void setProcessInputStream(OutputStream os) {
            this.stdinStream = os;
        }

        @Override
        public void setProcessErrorStream(InputStream is) {
            this.stderrStream = is;
        }

        @Override
        public void setProcessOutputStream(InputStream is) {
            this.stdoutStream = is;
        }

        @Override
        public void start() {
            if (stdinStream != null) {
                try {
                    stdinStream.close();
                } catch (IOException ignored) {
                }
            }
            stdoutFuture = stdoutStream != null
                    ? java.util.concurrent.CompletableFuture.runAsync(() -> readStream(stdoutStream, stdoutBuffer),
                            STREAM_READER_EXECUTOR)
                    : java.util.concurrent.CompletableFuture.completedFuture(null);
            stderrFuture = (!redirectErrorStream && stderrStream != null)
                    ? java.util.concurrent.CompletableFuture.runAsync(() -> readStream(stderrStream, stderrBuffer),
                            STREAM_READER_EXECUTOR)
                    : java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public void stop() {
            if (stdoutFuture != null && stderrFuture != null) {
                try {
                    java.util.concurrent.CompletableFuture.allOf(stdoutFuture, stderrFuture).get(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.debug("Output stream readers did not finish before stop timeout", e);
                }
            }
        }
    }

    static ExecutorService getStreamReaderExecutor() {
        return STREAM_READER_EXECUTOR;
    }

    static void readStream(InputStream inputStream, StringBuilder buffer) {
        readStream(inputStream, buffer, MAX_OUTPUT_CHARS);
    }

    static void readStream(InputStream inputStream, StringBuilder buffer, int maxChars) {
        try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            char[] chunk = new char[READ_CHUNK_SIZE];
            boolean truncated = false;
            for (int read = reader.read(chunk); read != -1; read = reader.read(chunk)) {
                if (!truncated) {
                    int remaining = maxChars - buffer.length();
                    if (read <= remaining) {
                        buffer.append(chunk, 0, read);
                    } else {
                        if (remaining > 0) {
                            buffer.append(chunk, 0, remaining);
                        }
                        buffer.append(TRUNCATION_SUFFIX);
                        truncated = true;
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Stream closed or failed while reading process output", e);
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

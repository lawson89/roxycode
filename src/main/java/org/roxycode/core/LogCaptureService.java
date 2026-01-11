package org.roxycode.core;

import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Singleton
public class LogCaptureService {

    private final List<String> logLines = Collections.synchronizedList(new ArrayList<>());
    private PrintStream originalOut;
    private PrintStream originalErr;

    public LogCaptureService() {
        startCapture();
    }

    private void startCapture() {
        originalOut = System.out;
        originalErr = System.err;

        System.setOut(new PrintStream(new DualOutputStream(originalOut, line -> addLogLine("[OUT] " + line)), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new DualOutputStream(originalErr, line -> addLogLine("[ERR] " + line)), true, StandardCharsets.UTF_8));
    }

    private void addLogLine(String line) {
        synchronized (logLines) {
            logLines.add(line);
            // We'll let the UI handle the actual trimming when displaying, 
            // but we should probably keep some limit here to avoid memory leaks.
            if (logLines.size() > 1000) {
                logLines.remove(0);
            }
        }
    }

    public List<String> getLogs(int count) {
        synchronized (logLines) {
            int size = logLines.size();
            int start = Math.max(0, size - count);
            return new ArrayList<>(logLines.subList(start, size));
        }
    }

    private static class DualOutputStream extends OutputStream {
        private final OutputStream original;
        private final java.util.function.Consumer<String> lineConsumer;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        public DualOutputStream(OutputStream original, java.util.function.Consumer<String> lineConsumer) {
            this.original = original;
            this.lineConsumer = lineConsumer;
        }

        @Override
        public void write(int b) throws IOException {
            original.write(b);
            if (b == '\n') {
                lineConsumer.accept(buffer.toString(StandardCharsets.UTF_8));
                buffer.reset();
            } else {
                buffer.write(b);
            }
        }

        @Override
        public void flush() throws IOException {
            original.flush();
        }

        @Override
        public void close() throws IOException {
            original.close();
        }
    }
}

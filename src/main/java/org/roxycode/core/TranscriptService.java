package org.roxycode.core;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Singleton
public class TranscriptService {
    private static final Logger LOG = LoggerFactory.getLogger(TranscriptService.class);
    private final RoxyProjectService projectService;
    private boolean enabled = false;
    private Path currentTranscriptFile;

    @Inject
    public TranscriptService(RoxyProjectService projectService) {
        this.projectService = projectService;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled && currentTranscriptFile == null) {
            initNewTranscript();
        } else if (!enabled) {
            currentTranscriptFile = null;
        }
    }

    private void initNewTranscript() {
        try {
            Path dir = projectService.getTranscriptsDir();
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            currentTranscriptFile = dir.resolve("transcript_" + timestamp + ".md");
            Files.writeString(currentTranscriptFile, "# Conversation Transcript - " + LocalDateTime.now() + "\n\n", StandardOpenOption.CREATE);
            LOG.info("Started new transcript: {}", currentTranscriptFile);
        } catch (IOException e) {
            LOG.error("Failed to initialize transcript file", e);
            enabled = false;
        }
    }

    public void log(String role, String message) {
        if (!enabled || currentTranscriptFile == null) return;

        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            String entry = String.format("### [%s] %s\n\n%s\n\n---\n\n", timestamp, role.toUpperCase(), message);
            Files.writeString(currentTranscriptFile, entry, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.error("Failed to write to transcript file", e);
        }
    }

    public void logToolCall(String name, Map<String, Object> args) {
        if (!enabled || currentTranscriptFile == null) return;
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            String entry = String.format("#### [%s] TOOL CALL: %s\n\nArguments:\n%s\n\n", timestamp, name, args);
            Files.writeString(currentTranscriptFile, entry, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.error("Failed to write tool call to transcript file", e);
        }
    }

    public void logToolResult(String name, String result) {
        if (!enabled || currentTranscriptFile == null) return;
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            String entry = String.format("#### [%s] TOOL RESULT: %s\n\nResult:\n%s\n\n---\n\n", timestamp, name, result);
            Files.writeString(currentTranscriptFile, entry, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.error("Failed to write tool result to transcript file", e);
        }
    }
}

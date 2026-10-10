package org.roxycode.app.ai.services;

import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Service providing safe file editing capabilities within the project sandbox.
 */
@AgentService(value = "fileEditorService", phases = {"CODE"})
@AgentDoc("Provides safe, ergonomic file editing capabilities for the AI agent with phase protection and security sandboxing.")
public class FileEditorService {

    private static final Logger log = LoggerFactory.getLogger(FileEditorService.class);

    private final ProjectService projectService;
    private final WorkflowService workflowService;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public FileEditorService(ProjectService projectService, WorkflowService workflowService, ApplicationEventPublisher eventPublisher) {
        this.projectService = projectService;
        this.workflowService = workflowService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Writes content to a file, overwriting it if it exists.
     *
     * @param relativePath the path relative to the project root
     * @param content the content to write
     * @throws IOException if an I/O error occurs
     */
    @AgentDoc(value = "Writes content to a file, overwriting it if it exists.", examples = {"fileEditorService.writeFile('path', 'content')"})
    public void writeFile(String relativePath, String content) throws IOException {
        checkPhase();
        Path path = resolvePath(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        log.info("Wrote file: {}", relativePath);
        eventPublisher.publishEvent(new FileChangedEvent(relativePath));
    }

    /**
     * Replaces a unique block of text with new content.
     *
     * @param relativePath the path relative to the project root
     * @param targetBlock the exact text block to replace
     * @param replacementBlock the new content
     * @throws IOException if an I/O error occurs
     * @throws IllegalArgumentException if the target block is not found or not unique
     */
    @AgentDoc(value = "Replaces a unique block of text with new content.", examples = {"fileEditorService.replaceBlock('path', 'target', 'replacement')"})
    public void replaceBlock(String relativePath, String targetBlock, String replacementBlock) throws IOException {
        checkPhase();
        Path path = resolvePath(relativePath);
        String content = Files.readString(path);

        String[] parts = targetBlock.trim().split("\s+");
        StringBuilder sb = new StringBuilder("(?s)");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            sb.append(java.util.regex.Pattern.quote(parts[i]));
            if (i < parts.length - 1) {
                sb.append("\\s+");
            }
        }
        String patternString = sb.toString();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(patternString);
        java.util.regex.Matcher matcher = pattern.matcher(content);

        if (!matcher.find()) {
            throw new IllegalArgumentException("TargetBlock not found in file: " + relativePath);
        }
        int start = matcher.start();
        int end = matcher.end();
        if (matcher.find()) {
            throw new IllegalArgumentException("TargetBlock is not unique in file: " + relativePath);
        }

        String newContent = content.substring(0, start) + replacementBlock + content.substring(end);
        Files.writeString(path, newContent);
        log.info("Replaced block in file: {}", relativePath);
        eventPublisher.publishEvent(new FileChangedEvent(relativePath));
    }

    /**
     * Replaces a range of lines with new content.
     *
     * @param relativePath the path relative to the project root
     * @param startLine the start line number (1-based)
     * @param endLine the end line number (1-based, inclusive)
     * @param replacementText the new content for the range
     * @throws IOException if an I/O error occurs
     */
    @AgentDoc(value = "Replaces a range of lines with new content using 1-based indexing.", examples = {"fileEditorService.replaceLines('path', 1, 5, 'new text')"})
    public void replaceLines(String relativePath, int startLine, int endLine, String replacementText) throws IOException {
        checkPhase();
        Path path = resolvePath(relativePath);
        List<String> lines = Files.readAllLines(path);

        if (startLine < 1 || startLine > lines.size() || endLine < startLine || endLine > lines.size()) {
            throw new IllegalArgumentException("Invalid line range: " + startLine + " to " + endLine);
        }

        List<String> newLines = new ArrayList<>();
        // Add lines before the range
        for (int i = 0; i < startLine - 1; i++) {
            newLines.add(lines.get(i));
        }

        // Add the replacement text
        newLines.add(replacementText);

        // Add lines after the range
        for (int i = endLine; i < lines.size(); i++) {
            newLines.add(lines.get(i));
        }

        Files.write(path, newLines);
        log.info("Replaced lines {}-{} in file: {}", startLine, endLine, relativePath);
        eventPublisher.publishEvent(new FileChangedEvent(relativePath));
    }

    /**
     * Inserts content at a specific line number.
     *
     * @param relativePath the path relative to the project root
     * @param lineNumber the line number to insert at (1-based)
     * @param content the content to insert
     * @throws IOException if an I/O error occurs
     */
    @AgentDoc(value = "Inserts content at a specific line number using 1-based indexing.", examples = {"fileEditorService.insertAtLine('path', 5, 'content')"})
    public void insertAtLine(String relativePath, int lineNumber, String content) throws IOException {
        checkPhase();
        Path path = resolvePath(relativePath);
        List<String> lines = Files.readAllLines(path);

        if (lineNumber < 1 || lineNumber > lines.size() + 1) {
            throw new IllegalArgumentException("Invalid line number: " + lineNumber);
        }

        List<String> newLines = new ArrayList<>(lines);
        newLines.add(lineNumber - 1, content);

        Files.write(path, newLines);
        log.info("Inserted content at line {} in file: {}", lineNumber, relativePath);
        eventPublisher.publishEvent(new FileChangedEvent(relativePath));
    }

    private void checkPhase() {
        if (workflowService.getCurrentPhase() != WorkflowPhase.CODE) {
            throw new IllegalStateException("Mutation methods are only allowed in the CODE phase. Current phase: " + workflowService.getCurrentPhase());
        }
    }

    private Path resolvePath(String relativePath) {
        Path root = projectService.getCurrentProjectRoot();
        if (root == null) {
            throw new IllegalStateException("No active project root set.");
        }
        root = root.toAbsolutePath().normalize();
        Path file = root.resolve(relativePath).toAbsolutePath().normalize();

        if (!file.startsWith(root)) {
            throw new SecurityException("Path traversal attempt detected: " + relativePath);
        }
        return file;
    }
}
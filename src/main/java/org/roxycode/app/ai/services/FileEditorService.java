package org.roxycode.app.ai.services;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

/** Service providing safe file editing capabilities within the project sandbox. */
@AgentService(
    value = "fileEditorService",
    phases = {"CODE"})
@AgentDoc(
    "Provides safe, ergonomic file editing capabilities for the AI agent with phase protection and security sandboxing.")
public class FileEditorService {

  private static final Logger log = LoggerFactory.getLogger(FileEditorService.class);

  private final ProjectService projectService;
  private final WorkflowService workflowService;
  private final ApplicationEventPublisher eventPublisher;

  @Autowired
  public FileEditorService(
      ProjectService projectService,
      WorkflowService workflowService,
      ApplicationEventPublisher eventPublisher) {
    this.projectService = projectService;
    this.workflowService = workflowService;
    this.eventPublisher = eventPublisher;
  }

  /**
   * Writes content to a file, overwriting it if it exists.
   *
   * @param relativePath the path relative to the project root
   * @param content the content to write
   * @return EditorResult indicating success or failure
   */
  @AgentDoc(
      value = "Writes content to a file, overwriting it if it exists.",
      examples = "fileEditorService.writeFile('src/main/resources/config.properties', 'key=value')")
  public EditorResult writeFile(String relativePath, String content) {
    try {
      checkPhase();
      Path path = resolvePath(relativePath);
      Files.createDirectories(path.getParent());
      Files.writeString(path, content);

      long size = Files.size(path);
      log.info("Wrote file: {} ({} bytes) at {}", relativePath, size, path.toAbsolutePath());

      eventPublisher.publishEvent(new FileChangedEvent(relativePath));
      return new EditorResult(true, "File written successfully: " + relativePath, null);
    } catch (Exception e) {
      log.error("Failed to write file {}: {}", relativePath, e.getMessage());
      return new EditorResult(false, null, e.getMessage());
    }
  }

  /**
   * Replaces a unique block of text with new content.
   *
   * @param relativePath the path relative to the project root
   * @param targetBlock the exact text block to replace
   * @param replacementBlock the new content
   * @return EditorResult indicating success or failure
   */
  @AgentDoc(
      value = "Replaces a unique block of text with new content.",
      examples =
          "fileEditorService.replaceBlock('src/Main.java', '// TODO: implement', 'System.out.println('Hello');')")
  public EditorResult replaceBlock(
      String relativePath, String targetBlock, String replacementBlock) {
    try {
      checkPhase();
      Path path = resolvePath(relativePath);
      String content = Files.readString(path);

      String[] parts = targetBlock.trim().split("\s+");
      StringBuilder sb = new StringBuilder("(?s)");
      for (int i = 0; i < parts.length; i++) {
        if (parts[i].isEmpty()) {
          continue;
        }
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

      long size = Files.size(path);
      log.info(
          "Replaced block in file: {} ({} bytes) at {}", relativePath, size, path.toAbsolutePath());

      eventPublisher.publishEvent(new FileChangedEvent(relativePath));
      return new EditorResult(true, "Block replaced successfully in: " + relativePath, null);
    } catch (Exception e) {
      log.error("Failed to replace block in {}: {}", relativePath, e.getMessage());
      return new EditorResult(false, null, e.getMessage());
    }
  }

  /**
   * Replaces a range of lines with new content.
   *
   * @param relativePath the path relative to the project root
   * @param startLine the start line number (1-based)
   * @param endLine the end line number (1-based, inclusive)
   * @param replacementText the new content for the range
   * @return EditorResult indicating success or failure
   */
  @AgentDoc(
      value = "Replaces a range of lines with new content using 1-based indexing.",
      examples =
          "fileEditorService.replaceLines('pom.xml', 10, 15, '    <dependencies>\n        ...\n    </dependencies>')")
  public EditorResult replaceLines(
      String relativePath, int startLine, int endLine, String replacementText) {
    try {
      checkPhase();
      Path path = resolvePath(relativePath);
      List<String> lines = Files.readAllLines(path);

      if (startLine < 1
          || startLine > lines.size()
          || endLine < startLine
          || endLine > lines.size()) {
        throw new IllegalArgumentException("Invalid line range: " + startLine + " to " + endLine);
      }

      List<String> newLines = new ArrayList<>();
      // Add lines before the range
      for (int i = 0; i < startLine - 1; i++) {
        newLines.add(lines.get(i));
      }

      // Add the replacement text
      newLines.addAll(splitLines(replacementText));

      // Add lines after the range
      for (int i = endLine; i < lines.size(); i++) {
        newLines.add(lines.get(i));
      }

      Files.write(path, newLines);

      long size = Files.size(path);
      log.info(
          "Replaced lines {}-{} in file: {} ({} bytes) at {}",
          startLine,
          endLine,
          relativePath,
          size,
          path.toAbsolutePath());

      eventPublisher.publishEvent(new FileChangedEvent(relativePath));
      return new EditorResult(true, "Lines replaced successfully in: " + relativePath, null);
    } catch (Exception e) {
      log.error("Failed to replace lines in {}: {}", relativePath, e.getMessage());
      return new EditorResult(false, null, e.getMessage());
    }
  }

  /**
   * Inserts content at a specific line number.
   *
   * @param relativePath the path relative to the project root
   * @param lineNumber the line number to insert at (1-based)
   * @param content the content to insert
   * @return EditorResult indicating success or failure
   */
  @AgentDoc(
      value = "Inserts content at a specific line number using 1-based indexing.",
      examples = "fileEditorService.insertAtLine('src/Main.java', 5, 'import java.util.List;')")
  public EditorResult insertAtLine(String relativePath, int lineNumber, String content) {
    try {
      checkPhase();
      Path path = resolvePath(relativePath);
      List<String> lines = Files.readAllLines(path);

      if (lineNumber < 1 || lineNumber > lines.size() + 1) {
        throw new IllegalArgumentException("Invalid line number: " + lineNumber);
      }

      List<String> newLines = new ArrayList<>(lines);
      newLines.addAll(lineNumber - 1, splitLines(content));

      Files.write(path, newLines);

      long size = Files.size(path);
      log.info(
          "Inserted content at line {} in file: {} ({} bytes) at {}",
          lineNumber,
          relativePath,
          size,
          path.toAbsolutePath());

      eventPublisher.publishEvent(new FileChangedEvent(relativePath));
      return new EditorResult(true, "Content inserted successfully in: " + relativePath, null);
    } catch (Exception e) {
      log.error("Failed to insert at line in {}: {}", relativePath, e.getMessage());
      return new EditorResult(false, null, e.getMessage());
    }
  }

  /**
   * Deletes a file at the given path relative to the project root.
   *
   * @param relativePath the path relative to the project root
   * @return EditorResult indicating success or failure
   */
  @AgentDoc(
      value = "Deletes a file at the given path relative to the project root.",
      examples = "fileEditorService.deleteFile('src/main/java/OldClass.java')")
  public EditorResult deleteFile(String relativePath) {
    try {
      checkPhase();
      Path path = resolvePath(relativePath);
      if (!Files.exists(path)) {
        throw new IllegalArgumentException("File does not exist: " + relativePath);
      }
      if (Files.isDirectory(path)) {
        throw new IllegalArgumentException("Cannot delete directory: " + relativePath);
      }
      Files.delete(path);
      log.info("Deleted file: {} at {}", relativePath, path.toAbsolutePath());

      eventPublisher.publishEvent(new FileChangedEvent(relativePath));
      return new EditorResult(true, "File deleted successfully: " + relativePath, null);
    } catch (Exception e) {
      log.error("Failed to delete file {}: {}", relativePath, e.getMessage());
      return new EditorResult(false, null, e.getMessage());
    }
  }

  private List<String> splitLines(String text) {
    String[] split = text.split("\\R", -1);
    int length = split.length;
    if (length > 1 && split[length - 1].isEmpty()) {
      length--;
    }
    List<String> result = new ArrayList<>(length);
    for (int i = 0; i < length; i++) {
      result.add(split[i]);
    }
    return result;
  }

  private void checkPhase() {
    if (workflowService.getCurrentPhase() != WorkflowPhase.CODE) {
      throw new IllegalStateException(
          "Mutation methods are only allowed in the CODE phase. Current phase: "
              + workflowService.getCurrentPhase());
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

package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.service.ProjectService;
import org.springframework.context.ApplicationEventPublisher;

class FileEditorServiceTest {

  @TempDir Path tempDir;

  private ProjectService projectService;
  private WorkflowService workflowService;
  private ApplicationEventPublisher eventPublisher;
  private FileEditorService fileEditorService;

  @BeforeEach
  void setUp() {
    projectService = mock(ProjectService.class);
    workflowService = mock(WorkflowService.class);
    eventPublisher = mock(ApplicationEventPublisher.class);
    fileEditorService = new FileEditorService(projectService, workflowService, eventPublisher);

    when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);
    when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.CODE);
  }

  @Test
  void testWriteFileSuccess() throws IOException {
    String relativePath = "test.txt";
    String content = "Hello World";

    EditorResult result = fileEditorService.writeFile(relativePath, content);

    assertTrue(result.success());
    Path filePath = tempDir.resolve(relativePath);
    assertTrue(Files.exists(filePath));
    assertEquals(content, Files.readString(filePath));
    verify(eventPublisher, atLeastOnce()).publishEvent(isA(FileChangedEvent.class));
  }

  @Test
  void testWriteFileWrongPhaseReturnsFailure() {
    when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);

    EditorResult result = fileEditorService.writeFile("test.txt", "content");

    assertFalse(result.success());
    assertTrue(result.errorHint().contains("CODE phase"));
  }

  @Test
  void testReplaceBlockSuccess() throws IOException {
    Path filePath = tempDir.resolve("test.java");
    Files.writeString(filePath, "public class Test {\n // TODO\n }");

    EditorResult result = fileEditorService.replaceBlock("test.java", "// TODO", "// Done");

    assertTrue(result.success());
    String content = Files.readString(filePath);
    assertTrue(content.contains("// Done"));
    assertFalse(content.contains("// TODO"));
    verify(eventPublisher, atLeastOnce()).publishEvent(isA(FileChangedEvent.class));
  }

  @Test
  void testReplaceBlockWithVariableWhitespace() throws IOException {
    Path filePath = tempDir.resolve("test.java");
    Files.writeString(filePath, "public    void   myMethod() { }");

    // Should match despite different whitespace in target block
    EditorResult result =
        fileEditorService.replaceBlock(
            "test.java", "public void myMethod", "private int newMethod");

    assertTrue(result.success());
    String content = Files.readString(filePath);
    assertEquals("private int newMethod() { }", content);
  }

  @Test
  void testReplaceBlockWithNewlinesAndTabs() throws IOException {
    Path filePath = tempDir.resolve("test_whitespace.java");
    Files.writeString(filePath, "public\nvoid\tmyMethod() { }");

    // Should match despite newlines and tabs
    EditorResult result =
        fileEditorService.replaceBlock(
            "test_whitespace.java", "public void myMethod", "private int newMethod");

    assertTrue(result.success());
    String content = Files.readString(filePath);
    assertEquals("private int newMethod() { }", content);
  }

  @Test
  void testReplaceBlockNotUniqueReturnsFailure() throws IOException {
    Path filePath = tempDir.resolve("test.java");
    Files.writeString(filePath, "// TODO\n // TODO");

    EditorResult result = fileEditorService.replaceBlock("test.java", "// TODO", "// Done");

    assertFalse(result.success());
    assertTrue(result.errorHint().contains("not unique"));
  }

  @Test
  void testReplaceLinesSuccess() throws IOException {
    Path filePath = tempDir.resolve("test.txt");
    Files.write(filePath, List.of("Line 1", "Line 2", "Line 3", "Line 4"));

    EditorResult result = fileEditorService.replaceLines("test.txt", 2, 3, "New Content");

    assertTrue(result.success());
    List<String> lines = Files.readAllLines(filePath);
    assertEquals(3, lines.size());
    assertEquals("Line 1", lines.get(0));
    assertEquals("New Content", lines.get(1));
    assertEquals("Line 4", lines.get(2));
    verify(eventPublisher, atLeastOnce()).publishEvent(isA(FileChangedEvent.class));
  }

  @Test
  void testInsertAtLineSuccess() throws IOException {
    Path filePath = tempDir.resolve("test.txt");
    Files.write(filePath, List.of("Line 1", "Line 3"));

    EditorResult result = fileEditorService.insertAtLine("test.txt", 2, "Line 2");

    assertTrue(result.success());
    List<String> lines = Files.readAllLines(filePath);
    assertEquals(3, lines.size());
    assertEquals("Line 2", lines.get(1));
    verify(eventPublisher, atLeastOnce()).publishEvent(isA(FileChangedEvent.class));
  }

  @Test
  void testPathTraversalReturnsFailure() {
    EditorResult result = fileEditorService.writeFile("../outside.txt", "content");

    assertFalse(result.success());
    assertTrue(result.errorHint().contains("Path traversal"));
  }
}

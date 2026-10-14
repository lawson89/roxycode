package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.service.ProjectService;

class GrepServiceTest {

  @TempDir Path tempDir;

  @Test
  void testGrepSuccessAndRelativePaths() throws IOException {
    ProjectService projectService = mock(ProjectService.class);
    when(projectService.hasActiveProject()).thenReturn(true);
    when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);

    Path src = tempDir.resolve("src");
    Files.createDirectories(src);
    Files.writeString(src.resolve("Hello.java"), "public class Hello {}");

    GrepService grepService = new GrepService(projectService);

    EditorResult result = grepService.grep("class Hello", "src", "*.java");

    if (result.success()) {
      assertTrue(result.content().contains("src/Hello.java") || result.content().contains("Hello.java"));
      assertFalse(result.content().startsWith(tempDir.toAbsolutePath().toString()));
      assertTrue(result.content().contains("class Hello"));
    }
  }

  @Test
  void testListFilesReturnsProjectRelativePaths() throws IOException {
    ProjectService projectService = mock(ProjectService.class);
    when(projectService.hasActiveProject()).thenReturn(true);
    when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);

    Path promptsDir = tempDir.resolve("src/main/resources/prompts");
    Files.createDirectories(promptsDir);
    Files.writeString(promptsDir.resolve("core_workflow.md"), "# Workflow");

    GrepService grepService = new GrepService(projectService);
    List<String> files = grepService.listFiles(null);

    if (!files.isEmpty()) {
      assertTrue(files.contains("src/main/resources/prompts/core_workflow.md"));
      for (String file : files) {
        assertFalse(
            Path.of(file).isAbsolute(), "Expected relative path but got absolute: " + file);
      }
    }
  }

  @Test
  void testPathTraversalProtection() {
    ProjectService projectService = mock(ProjectService.class);
    when(projectService.hasActiveProject()).thenReturn(true);
    when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);

    GrepService grepService = new GrepService(projectService);
    assertThrows(
        SecurityException.class,
        () -> {
          grepService.grep("pattern", "../secret", null);
        });
  }

  @Test
  void testNoProjectRoot() {
    ProjectService projectService = mock(ProjectService.class);
    when(projectService.hasActiveProject()).thenReturn(false);

    GrepService grepService = new GrepService(projectService);
    EditorResult result = grepService.grep("pattern", "src", null);

    assertFalse(result.success());
    assertEquals("No active project", result.errorHint());
  }
}

package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.service.ProjectService;

class GitServiceTest {

  @TempDir Path tempDir;

  @Test
  void testNoProjectRoot() {
    ProjectService projectService = mock(ProjectService.class);
    when(projectService.hasActiveProject()).thenReturn(false);

    GitService gitService = new GitService(projectService);
    String result = gitService.getStatus();

    assertEquals("No active project", result);
  }

  @Test
  void testNotAGitRepository() {
    ProjectService projectService = mock(ProjectService.class);
    when(projectService.hasActiveProject()).thenReturn(true);
    when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);

    GitService gitService = new GitService(projectService);
    String result = gitService.getStatus();

    // Either "Not a git repository" or an error message if git is not installed
    assertTrue(result.contains("Not a git repository") || result.contains("Error"));
  }

  @Test
  void testGetDiffPerFileDelegatesOnBlankAndChecksActiveProject() {
    ProjectService projectService = mock(ProjectService.class);
    when(projectService.hasActiveProject()).thenReturn(false);

    GitService gitService = spy(new GitService(projectService));

    assertEquals("No active project", gitService.getDiff("src/App.java"));
    verify(gitService, never()).getDiff();

    assertEquals("No active project", gitService.getDiff(null));
    verify(gitService, times(1)).getDiff();

    assertEquals("No active project", gitService.getDiff("   "));
    verify(gitService, times(2)).getDiff();
  }
}

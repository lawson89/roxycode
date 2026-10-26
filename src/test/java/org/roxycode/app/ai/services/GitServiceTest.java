package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.roxycode.app.ai.AgentJexlPermissions;
import org.roxycode.app.ai.LLMDocGenerator;
import org.roxycode.app.service.ProcessRunner;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.roxycode.app.service.ProjectService;

class GitServiceTest {

    @TempDir Path tempDir;

    private ProjectService createActiveProjectService() {
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);
        lenient().when(projectService.resolvePath(any())).thenCallRealMethod();
        lenient().when(projectService.resolveRelativePath(any())).thenCallRealMethod();
        return projectService;
    }

    @Test
    void testNoProjectRoot() {
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.hasActiveProject()).thenReturn(false);

        GitService gitService = new GitService(projectService);
        assertEquals("No active project", gitService.getStatus());
        assertEquals("No active project", gitService.getDiff());
        assertEquals("No active project", gitService.getDiffHead());
        assertEquals(List.of(), gitService.getChangedFiles());
        assertEquals("No active project", gitService.getBlame("Tracked.java", 1, 2));
    }

    @Test
    void testNotAGitRepository() {
        ProjectService projectService = createActiveProjectService();
        GitService gitService = new GitService(projectService);

        try (MockedStatic<ProcessRunner> runnerMock = mockStatic(ProcessRunner.class)) {
            runnerMock
                    .when(() -> ProcessRunner.run(anyList(), any(), anyMap()))
                    .thenReturn(
                            new ProcessResult(
                                    128,
                                    "fatal: not a git repository (or any of the parent directories): .git\n",
                                    "",
                                    false));

            String result = gitService.getStatus();

            assertEquals("Not a git repository", result);
        }
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

    @Test
    void testGetDiffIncludesUnstagedStagedAndUntrackedFiles() throws IOException {
        ProjectService projectService = createActiveProjectService();
        GitService gitService = new GitService(projectService);

        // Create an untracked file on disk so GitService can format its unified diff
        Path subDir = tempDir.resolve("src");
        Files.createDirectories(subDir);
        Files.writeString(
                subDir.resolve("NewFeature.java"),
                "package src;" + (char) 10 + "public class NewFeature {}" + (char) 10);

        String headDiffOut =
                String.join(
                        String.valueOf((char) 10),
                        "diff --git a/Tracked.java b/Tracked.java",
                        "--- a/Tracked.java",
                        "+++ b/Tracked.java",
                        "@@ -1,2 +1,2 @@",
                        "+line 1 staged",
                        "+line 2 unstaged");

        String statusOut =
                String.join(
                        String.valueOf((char) 10),
                        "MM Tracked.java",
                        "R  OldName.java -> Renamed.java",
                        "?? src/NewFeature.java");

        try (MockedStatic<ProcessRunner> runner = mockStatic(ProcessRunner.class)) {
            runner.when(
                            () ->
                                    ProcessRunner.run(
                                            eq(List.of("git", "diff", "HEAD")),
                                            eq(tempDir),
                                            anyMap()))
                    .thenReturn(new ProcessResult(0, headDiffOut, "", false));

            runner.when(
                            () ->
                                    ProcessRunner.run(
                                            eq(
                                                    List.of(
                                                            "git",
                                                            "ls-files",
                                                            "--others",
                                                            "--exclude-standard")),
                                            eq(tempDir),
                                            anyMap()))
                    .thenReturn(new ProcessResult(0, "src/NewFeature.java", "", false));

            runner.when(
                            () ->
                                    ProcessRunner.run(
                                            eq(
                                                    List.of(
                                                            "git",
                                                            "diff",
                                                            "HEAD",
                                                            "--",
                                                            "src/NewFeature.java")),
                                            eq(tempDir),
                                            anyMap()))
                    .thenReturn(new ProcessResult(0, "", "", false));

            runner.when(
                            () ->
                                    ProcessRunner.run(
                                            eq(
                                                    List.of(
                                                            "git",
                                                            "ls-files",
                                                            "--others",
                                                            "--exclude-standard",
                                                            "--",
                                                            "src/NewFeature.java")),
                                            eq(tempDir),
                                            anyMap()))
                    .thenReturn(new ProcessResult(0, "src/NewFeature.java", "", false));

            runner.when(
                            () ->
                                    ProcessRunner.run(
                                            eq(List.of("git", "status", "--porcelain", "-uall")),
                                            eq(tempDir),
                                            anyMap()))
                    .thenReturn(new ProcessResult(0, statusOut, "", false));

            String fullDiff = gitService.getDiff();
            assertTrue(fullDiff.contains("+line 1 staged"), "Should include staged changes");
            assertTrue(fullDiff.contains("+line 2 unstaged"), "Should include unstaged changes");
            assertTrue(
                    fullDiff.contains("diff --git a/src/NewFeature.java b/src/NewFeature.java"),
                    "Should include untracked file git header");
            assertTrue(
                    fullDiff.contains("+++ b/src/NewFeature.java"),
                    "Should include untracked file header");
            assertTrue(
                    fullDiff.contains("@@ -0,0 +1,2 @@"),
                    "Should include untracked file hunk header");
            assertTrue(
                    fullDiff.contains("+public class NewFeature {}"),
                    "Should include untracked file lines");

            // Per-file diff for untracked file
            String untrackedOnlyDiff = gitService.getDiff("src/NewFeature.java");
            assertTrue(untrackedOnlyDiff.contains("+++ b/src/NewFeature.java"));
            assertTrue(untrackedOnlyDiff.contains("+public class NewFeature {}"));
            assertFalse(untrackedOnlyDiff.contains("Tracked.java"));

            // getDiffHead() includes staged + unstaged tracked changes, but not untracked
            String headDiff = gitService.getDiffHead();
            assertTrue(headDiff.contains("+line 1 staged"));
            assertTrue(headDiff.contains("+line 2 unstaged"));
            assertFalse(headDiff.contains("NewFeature.java"));

            // getChangedFiles() lists modified, renamed, and untracked files
            List<String> changedFiles = gitService.getChangedFiles();
            assertEquals(
                    List.of("Tracked.java", "Renamed.java", "src/NewFeature.java"), changedFiles);
        }
    }

    @Test
    void testGetChangedFilesPreservesFirstLineLeadingSpaceAndUnquotesEscapedPaths() {
        ProjectService projectService = createActiveProjectService();
        GitService gitService = new GitService(projectService);

        char dq = (char) 34;
        char bs = (char) 92;
        String quotedEscaped =
                "?? " + dq + "dir with spaces/quote" + bs + dq + "file" + bs + bs + "name.txt" + dq;
        String statusOut =
                String.join(
                        String.valueOf((char) 10),
                        " M FirstUnstaged.java",
                        quotedEscaped,
                        "R  " + dq + "old name.txt" + dq + " -> " + dq + "new name.txt" + dq);

        try (MockedStatic<ProcessRunner> runner = mockStatic(ProcessRunner.class)) {
            runner.when(
                            () ->
                                    ProcessRunner.run(
                                            eq(List.of("git", "status", "--porcelain", "-uall")),
                                            eq(tempDir),
                                            anyMap()))
                    .thenReturn(new ProcessResult(0, statusOut + (char) 10, "", false));

            List<String> changedFiles = gitService.getChangedFiles();
            assertEquals(
                    List.of(
                            "FirstUnstaged.java",
                            "dir with spaces/quote" + dq + "file/name.txt",
                            "new name.txt"),
                    changedFiles);
        }
    }

    @Test
    void testGetDiffHeadFallbackWhenUnbornHead() {
        ProjectService projectService = createActiveProjectService();
        GitService gitService = new GitService(projectService);

        try (MockedStatic<ProcessRunner> runner = mockStatic(ProcessRunner.class)) {
            runner.when(
                            () ->
                                    ProcessRunner.run(
                                            eq(List.of("git", "diff", "HEAD")),
                                            eq(tempDir),
                                            anyMap()))
                    .thenReturn(
                            new ProcessResult(
                                    128,
                                    "fatal: ambiguous argument 'HEAD'",
                                    "fatal: ambiguous argument 'HEAD'",
                                    false));

            runner.when(
                            () ->
                                    ProcessRunner.run(
                                            eq(List.of("git", "diff", "--cached")),
                                            eq(tempDir),
                                            anyMap()))
                    .thenReturn(new ProcessResult(0, "+staged line", "", false));

            runner.when(() -> ProcessRunner.run(eq(List.of("git", "diff")), eq(tempDir), anyMap()))
                    .thenReturn(new ProcessResult(0, "+unstaged line", "", false));

            String diff = gitService.getDiffHead();
            assertTrue(diff.contains("+staged line"));
            assertTrue(diff.contains("+unstaged line"));
        }
    }

    @Test
    void testGetBlameAndValidation() {
        ProjectService projectService = createActiveProjectService();
        GitService gitService = new GitService(projectService);

        try (MockedStatic<ProcessRunner> runner = mockStatic(ProcessRunner.class)) {
            String blameOut =
                    "abcd1234 (Roxy Test 2025-01-01 1) line 1"
                            + (char) 10
                            + "abcd1234 (Roxy Test 2025-01-01 2) line 2";
            runner.when(
                            () ->
                                    ProcessRunner.run(
                                            eq(
                                                    List.of(
                                                            "git",
                                                            "blame",
                                                            "-L",
                                                            "1,2",
                                                            "--",
                                                            "Tracked.java")),
                                            eq(tempDir),
                                            anyMap()))
                    .thenReturn(new ProcessResult(0, blameOut, "", false));

            String blame = gitService.getBlame("Tracked.java", 1, 2);
            assertTrue(blame.contains("line 1"));
            assertTrue(blame.contains("line 2"));
            assertTrue(blame.contains("Roxy Test"));
        }

        assertThrows(IllegalArgumentException.class, () -> gitService.getBlame("", 1, 2));
        assertThrows(
                IllegalArgumentException.class, () -> gitService.getBlame("Tracked.java", 0, 2));
        assertThrows(
                IllegalArgumentException.class, () -> gitService.getBlame("Tracked.java", 3, 2));
        assertThrows(SecurityException.class, () -> gitService.getBlame("../outside.txt", 1, 2));
    }

    @Test
    void testNewMethodsExposedInJexlPermissionsAndLlmDocGenerator() throws Exception {
        ProjectService projectService = createActiveProjectService();
        GitService gitService = new GitService(projectService);

        assertTrue(
                AgentJexlPermissions.INSTANCE.allow(
                        GitService.class, GitService.class.getMethod("getDiffHead")));
        assertTrue(
                AgentJexlPermissions.INSTANCE.allow(
                        GitService.class, GitService.class.getMethod("getDiffHead", String.class)));
        assertTrue(
                AgentJexlPermissions.INSTANCE.allow(
                        GitService.class, GitService.class.getMethod("getChangedFiles")));
        assertTrue(
                AgentJexlPermissions.INSTANCE.allow(
                        GitService.class,
                        GitService.class.getMethod(
                                "getBlame", String.class, int.class, int.class)));

        String doc = new LLMDocGenerator().generateDoc(List.of(gitService));
        assertTrue(doc.contains("getDiffHead(): string;"));
        assertTrue(doc.contains("getDiffHead(relativePath: string): string;"));
        assertTrue(doc.contains("getChangedFiles(): List;"));
        assertTrue(
                doc.contains(
                        "getBlame(relativePath: string, startLine: number, endLine: number): string;"));
    }
}

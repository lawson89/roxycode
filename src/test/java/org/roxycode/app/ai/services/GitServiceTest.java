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
import org.roxycode.app.service.ProcessRunner;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.ui.SwingTestUtils;

class GitServiceTest {

    @TempDir
    Path tempDir;

    private ProjectService createActiveProjectService() {
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);
        lenient().when(projectService.resolvePath(any())).thenCallRealMethod();
        lenient().when(projectService.resolveRelativePath(any())).thenCallRealMethod();
        return projectService;
    }

    private void stubGit(MockedStatic<ProcessRunner> runner, int exit, String out, String... args) {
        runner.when(() -> ProcessRunner.run(eq(List.of(args)), eq(tempDir), anyMap()))
                .thenReturn(new ProcessResult(exit, out, exit == 0 ? "" : out, false));
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
        GitService gitService = new GitService(createActiveProjectService());
        try (MockedStatic<ProcessRunner> runnerMock = mockStatic(ProcessRunner.class)) {
            runnerMock.when(() -> ProcessRunner.run(anyList(), any(), anyMap())).thenReturn(new ProcessResult(128,
                    "fatal: not a git repository (or any of the parent directories): .git\n", "", false));
            assertEquals("Not a git repository", gitService.getStatus());
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
        GitService gitService = new GitService(createActiveProjectService());
        Files.writeString(Files.createDirectories(tempDir.resolve("src")).resolve("NewFeature.java"),
                "package src;\npublic class NewFeature {}\n");

        String headDiffOut = String.join("\n", "diff --git a/Tracked.java b/Tracked.java", "--- a/Tracked.java",
                "+++ b/Tracked.java", "@@ -1,2 +1,2 @@", "+line 1 staged", "+line 2 unstaged");
        String statusOut = String.join("\n", "MM Tracked.java", "R  OldName.java -> Renamed.java",
                "?? src/NewFeature.java");
        String noIndexDiffOut = String.join("\n", "diff --git a//dev/null b/src/NewFeature.java",
                "new file mode 100644", "index 0000000..1111111", "--- /dev/null", "+++ b/src/NewFeature.java",
                "@@ -0,0 +1,2 @@", "+package src;", "+public class NewFeature {}");

        try (MockedStatic<ProcessRunner> runner = mockStatic(ProcessRunner.class)) {
            stubGit(runner, 0, headDiffOut, "git", "diff", "HEAD");
            stubGit(runner, 0, "src/NewFeature.java", "git", "ls-files", "--others", "--exclude-standard");
            stubGit(runner, 1, noIndexDiffOut, "git", "diff", "--no-index", "--", "/dev/null", "src/NewFeature.java");
            stubGit(runner, 0, "", "git", "diff", "HEAD", "--", "src/NewFeature.java");
            stubGit(runner, 0, "src/NewFeature.java", "git", "ls-files", "--others", "--exclude-standard", "--",
                    "src/NewFeature.java");
            stubGit(runner, 0, statusOut, "git", "-c", "core.quotePath=false", "status", "--porcelain", "-uall");

            SwingTestUtils.assertContainsAll(gitService.getDiff(), "+line 1 staged", "+line 2 unstaged",
                    "diff --git a/src/NewFeature.java b/src/NewFeature.java", "+++ b/src/NewFeature.java",
                    "@@ -0,0 +1,2 @@", "+public class NewFeature {}");

            String untrackedOnlyDiff = gitService.getDiff("src/NewFeature.java");
            SwingTestUtils.assertContainsAll(untrackedOnlyDiff,
                    "diff --git a/src/NewFeature.java b/src/NewFeature.java", "+++ b/src/NewFeature.java",
                    "+public class NewFeature {}");
            assertFalse(untrackedOnlyDiff.contains("Tracked.java"));

            String headDiff = gitService.getDiffHead();
            SwingTestUtils.assertContainsAll(headDiff, "+line 1 staged", "+line 2 unstaged");
            assertFalse(headDiff.contains("NewFeature.java"));

            assertEquals(List.of("Tracked.java", "Renamed.java", "src/NewFeature.java"), gitService.getChangedFiles());
        }
        assertEquals(
                "diff --git a/bin/logo.png b/bin/logo.png\nnew file mode 100644\nBinary files /dev/null and b/bin/logo.png differ",
                GitService.normalizeUntrackedNoIndexDiff("bin/logo.png",
                        "diff --git a//dev/null b/bin/logo.png\nnew file mode 100644\nBinary files /dev/null and b/bin/logo.png differ\n"));
    }

    @Test
    void testGetChangedFilesPreservesFirstLineLeadingSpaceAndUnquotesEscapedPaths() {
        GitService gitService = new GitService(createActiveProjectService());
        char dq = (char) 34;
        char bs = (char) 92;
        String statusOut = String.join("\n", " M FirstUnstaged.java",
                "?? " + dq + "dir with spaces/quote" + bs + dq + "file" + bs + bs + "name.txt" + dq,
                "R  " + dq + "old name.txt" + dq + " -> " + dq + "new name.txt" + dq);

        try (MockedStatic<ProcessRunner> runner = mockStatic(ProcessRunner.class)) {
            stubGit(runner, 0, statusOut + (char) 10, "git", "-c", "core.quotePath=false", "status", "--porcelain",
                    "-uall");
            assertEquals(List.of("FirstUnstaged.java", "dir with spaces/quote" + dq + "file/name.txt", "new name.txt"),
                    gitService.getChangedFiles());
        }

        assertEquals("café.txt", GitService.unquotePorcelainPath(dq + "caf" + bs + "303" + bs + "251.txt" + dq));
        assertEquals("dir/über_файл.java",
                GitService.unquotePorcelainPath(dq + "dir/" + bs + "303" + bs + "274ber_" + bs + "321" + bs + "204" + bs
                        + "320" + bs + "260" + bs + "320" + bs + "271" + bs + "320" + bs + "273.java" + dq));
        assertEquals("plain.txt", GitService.unquotePorcelainPath("plain.txt"));
        assertEquals("", GitService.unquotePorcelainPath(null));
    }

    @Test
    void testGetDiffHeadFallbackWhenUnbornHead() {
        GitService gitService = new GitService(createActiveProjectService());
        try (MockedStatic<ProcessRunner> runner = mockStatic(ProcessRunner.class)) {
            stubGit(runner, 128, "fatal: ambiguous argument 'HEAD'", "git", "diff", "HEAD");
            stubGit(runner, 0, "+staged line", "git", "diff", "--cached");
            stubGit(runner, 0, "+unstaged line", "git", "diff");

            SwingTestUtils.assertContainsAll(gitService.getDiffHead(), "+staged line", "+unstaged line");
        }
    }

    @Test
    void testGetBlameAndValidation() {
        GitService gitService = new GitService(createActiveProjectService());
        try (MockedStatic<ProcessRunner> runner = mockStatic(ProcessRunner.class)) {
            stubGit(runner, 0, "abcd1234 (Roxy Test 2025-01-01 1) line 1\nabcd1234 (Roxy Test 2025-01-01 2) line 2",
                    "git", "blame", "-L", "1,2", "--", "Tracked.java");
            SwingTestUtils.assertContainsAll(gitService.getBlame("Tracked.java", 1, 2), "line 1", "line 2",
                    "Roxy Test");
        }

        assertThrows(IllegalArgumentException.class, () -> gitService.getBlame("", 1, 2));
        assertThrows(IllegalArgumentException.class, () -> gitService.getBlame("Tracked.java", 0, 2));
        assertThrows(IllegalArgumentException.class, () -> gitService.getBlame("Tracked.java", 3, 2));
        assertThrows(SecurityException.class, () -> gitService.getBlame("../outside.txt", 1, 2));
    }
}

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
        String errOut = out;
        if (exit == 0) {
            errOut = "";
        }
        runner.when(() -> ProcessRunner.run(eq(List.of(args)), eq(tempDir), anyMap()))
                .thenReturn(new ProcessResult(exit, out, errOut, false));
    }

    @Test
    void testNoProjectRootAndNotAGitRepository() {
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.hasActiveProject()).thenReturn(false);
        GitService gitService = spy(new GitService(projectService));

        assertEquals("No active project", gitService.getStatus());
        assertEquals("No active project", gitService.getDiff("src/App.java"));
        verify(gitService, never()).getDiff();
        assertEquals("No active project", gitService.getDiff(null));
        assertEquals("No active project", gitService.getDiff("   "));
        verify(gitService, times(2)).getDiff();
        assertEquals("No active project", gitService.getDiffHead());
        assertEquals(List.of(), gitService.getChangedFiles());
        assertEquals("No active project", gitService.getBlame("Tracked.java", 1, 2));

        GitService activeGitService = new GitService(createActiveProjectService());
        try (MockedStatic<ProcessRunner> runnerMock = mockStatic(ProcessRunner.class)) {
            runnerMock.when(() -> ProcessRunner.run(anyList(), any(), anyMap())).thenReturn(new ProcessResult(128,
                    "fatal: not a git repository (or any of the parent directories): .git\n", "", false));
            assertEquals("Not a git repository", activeGitService.getStatus());
        }
    }

    @Test
    void testGetDiffIncludesUnstagedStagedAndUntrackedFiles() throws IOException {
        GitService gitService = new GitService(createActiveProjectService());
        Files.writeString(Files.createDirectories(tempDir.resolve("src")).resolve("NewFeature.java"),
                "package src;\npublic class NewFeature {}\n");

        String headDiffOut = String.join("\n", "diff --git a/Tracked.java b/Tracked.java", "--- a/Tracked.java",
                "+++ b/Tracked.java", "@@ -1,2 +1,2 @@", "+line 1 staged", "+line 2 unstaged");
        String nl = "\n";
        String statusOut = String.join(nl, "MM Tracked.java", "R  OldName.java -> Renamed.java",
                "?? src/NewFeature.java");
        String noIndexDiffOut = String.join(nl, "diff --git a//dev/null b/src/NewFeature.java", "new file mode 100644",
                "index 0000000..1111111", "--- /dev/null", "+++ b/src/NewFeature.java", "@@ -0,0 +1,2 @@",
                "+package src;", "+public class NewFeature {}");
        try (MockedStatic<ProcessRunner> runner = mockStatic(ProcessRunner.class)) {
            stubGit(runner, 0, headDiffOut, "git", "diff", "HEAD");
            stubGit(runner, 0, "src/NewFeature.java", "git", "ls-files", "--others", "--exclude-standard");
            stubGit(runner, 1, noIndexDiffOut, "git", "diff", "--no-index", "--src-prefix=a/", "--dst-prefix=b/", "--",
                    "/dev/null", "src/NewFeature.java");
            stubGit(runner, 0, "", "git", "diff", "HEAD", "--", "src/NewFeature.java");
            stubGit(runner, 0, "src/NewFeature.java", "git", "ls-files", "--others", "--exclude-standard", "--",
                    "src/NewFeature.java");
            stubGit(runner, 0, statusOut, "git", "-c", "core.quotePath=false", "status", "--porcelain=v1", "-uall");
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
    }

    @Test
    void testGetChangedFilesPreservesFirstLineLeadingSpaceAndHandlesPorcelainV1Paths() {
        GitService gitService = new GitService(createActiveProjectService());
        char dq = '\"';
        char bs = '\\';
        String nl = "\n";
        String statusOut = String.join(nl, " M FirstUnstaged.java",
                "?? dir with spaces/quote" + dq + "file" + bs + "name.txt", "R  old name.txt -> new name.txt",
                "?? café_über_файл.java");

        try (MockedStatic<ProcessRunner> runner = mockStatic(ProcessRunner.class)) {
            stubGit(runner, 0, statusOut, "git", "-c", "core.quotePath=false", "status", "--porcelain=v1", "-uall");
            assertEquals(List.of("FirstUnstaged.java", "dir with spaces/quote" + dq + "file/name.txt", "new name.txt",
                    "café_über_файл.java"), gitService.getChangedFiles());
        }
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

    @Test
    void testGetDefaultBranchAndBranchComparisonMethods() throws IOException {
        ProjectService inactiveProject = mock(ProjectService.class);
        when(inactiveProject.hasActiveProject()).thenReturn(false);
        GitService inactiveGit = new GitService(inactiveProject);
        assertEquals("No active project", inactiveGit.getDefaultBranch());
        assertEquals("No active project", inactiveGit.getBranchLog());
        assertEquals(List.of(), inactiveGit.getBranchChangedFiles());
        assertEquals("No active project", inactiveGit.getBranchDiff());

        GitService gitService = new GitService(createActiveProjectService());
        String nl = String.valueOf((char) 10);
        Files.writeString(Files.createDirectories(tempDir.resolve("src")).resolve("UntrackedBranchFile.java"),
                "public class UntrackedBranchFile {}" + nl);

        try (MockedStatic<ProcessRunner> runner = mockStatic(ProcessRunner.class)) {
            // 1. origin/HEAD resolves default branch directly
            stubGit(runner, 0, "refs/remotes/origin/develop" + nl, "git", "symbolic-ref", "refs/remotes/origin/HEAD");
            assertEquals("develop", gitService.getDefaultBranch());

            // 2. Fallback when origin/HEAD is missing to show-ref --verify (e.g. master)
            stubGit(runner, 128, "fatal: ref refs/remotes/origin/HEAD is not a symbolic ref", "git", "symbolic-ref",
                    "refs/remotes/origin/HEAD");
            stubGit(runner, 1, "", "git", "show-ref", "--verify", "refs/heads/main");
            stubGit(runner, 1, "", "git", "show-ref", "--verify", "refs/remotes/origin/main");
            stubGit(runner, 0, "abcd1234 refs/heads/master", "git", "show-ref", "--verify", "refs/heads/master");
            assertEquals("master", gitService.getDefaultBranch());

            // 3. Branch log, changed files, and diff using default branch or explicit baseBranch
            stubGit(runner, 0, "refs/remotes/origin/main" + nl, "git", "symbolic-ref", "refs/remotes/origin/HEAD");
            stubGit(runner, 0, "base1234" + nl, "git", "merge-base", "main", "HEAD");
            stubGit(runner, 0, String.join(nl, "c222222 feat: second", "c111111 feat: first"), "git", "log",
                    "base1234..HEAD", "--oneline");
            stubGit(runner, 0, String.join(nl, "src/CommittedA.java", "src/Shared.java") + nl, "git", "diff",
                    "--name-only", "base1234...HEAD");
            stubGit(runner, 0, String.join(nl, " M src/Shared.java", "?? src/UntrackedBranchFile.java"), "git", "-c",
                    "core.quotePath=false", "status", "--porcelain=v1", "-uall");
            stubGit(runner, 0,
                    String.join(nl, "diff --git a/src/CommittedA.java b/src/CommittedA.java", "+committed line"), "git",
                    "diff", "base1234");
            stubGit(runner, 0, "src/UntrackedBranchFile.java", "git", "ls-files", "--others", "--exclude-standard");
            stubGit(runner, 1, String.join(nl, "diff --git a//dev/null b/src/UntrackedBranchFile.java", "--- /dev/null",
                    "+++ b/src/UntrackedBranchFile.java", "@@ -0,0 +1,1 @@", "+public class UntrackedBranchFile {}"),
                    "git", "diff", "--no-index", "--src-prefix=a/", "--dst-prefix=b/", "--", "/dev/null",
                    "src/UntrackedBranchFile.java");

            assertEquals(String.join(nl, "c222222 feat: second", "c111111 feat: first"), gitService.getBranchLog());
            assertEquals(List.of("src/CommittedA.java", "src/Shared.java", "src/UntrackedBranchFile.java"),
                    gitService.getBranchChangedFiles());
            SwingTestUtils.assertContainsAll(gitService.getBranchDiff(), "+committed line",
                    "diff --git a/src/UntrackedBranchFile.java b/src/UntrackedBranchFile.java",
                    "+public class UntrackedBranchFile {}");
        }
    }
}

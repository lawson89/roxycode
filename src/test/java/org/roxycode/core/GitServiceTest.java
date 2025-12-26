package org.roxycode.core;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zeroturnaround.exec.ProcessExecutor;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class GitServiceTest {

    @Inject
    GitService gitService;

    @Test
    void testGitCommands(@TempDir Path tempDir) throws Exception {
        // Setup a real temp git repo
        runCommand(tempDir.toFile(), "git", "init");
        runCommand(tempDir.toFile(), "git", "config", "user.email", "test@test.com");
        runCommand(tempDir.toFile(), "git", "config", "user.name", "Test User");

        // Test Branch (should be master or main depending on git config, or empty if no commits)
        // We need a commit to have a branch usually
        File testFile = tempDir.resolve("test.txt").toFile();
        testFile.createNewFile();
        runCommand(tempDir.toFile(), "git", "add", ".");
        runCommand(tempDir.toFile(), "git", "commit", "-m", "Initial");

        // 1. Verify Branch
        String branch = gitService.getCurrentBranch(tempDir);
        assertNotNull(branch);
        assertFalse(branch.isEmpty());
        // Note: Could be 'master' or 'main' depending on system config

        // 2. Verify Status
        // Create a change
        File newFile = tempDir.resolve("new.txt").toFile();
        newFile.createNewFile();

        String status = gitService.getStatus(tempDir);
        assertTrue(status.contains("?? new.txt") || status.contains("new.txt"));
    }

    private void runCommand(File dir, String... command) throws Exception {
        new ProcessExecutor()
                .command(command)
                .directory(dir)
                .timeout(5, TimeUnit.SECONDS)
                .execute();
    }
}
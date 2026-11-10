package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.ai.services.BuildContainerService.SandboxConfig;
import org.roxycode.app.service.ProcessRunner;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.roxycode.app.service.SystemToolService;

class NativeSandboxHardeningIntegrationTest {

    @TempDir
    Path tempDir;

    private BuildContainerService buildContainerService;
    private SystemToolService systemToolService;

    @BeforeEach
    void setUp() {
        systemToolService = mock(SystemToolService.class);
        SystemToolService realService = new SystemToolService(null, null, null);
        boolean available = realService.checkNativeSandboxAvailable();
        when(systemToolService.checkNativeSandboxAvailable()).thenReturn(available);

        buildContainerService = new BuildContainerService(systemToolService);
    }

    @Test
    void testNativeSandboxHardening() throws Exception {
        assumeTrue(buildContainerService.isNativeSandboxAvailable(),
                "Native sandbox (bwrap or seatbelt) must be available");

        Path projectRoot = Files.createDirectories(tempDir.resolve("project"));
        SandboxConfig config = new SandboxConfig("native", List.of(), "unused", "/workspace");

        // 1. Verify Read Denied for sensitive host path
        Path hostFile = Files.writeString(tempDir.resolve("host-sensitive.txt"), "secret");

        List<String> readCmd = buildContainerService.buildNativeSandboxCommand(projectRoot, config,
                "cat " + hostFile.toAbsolutePath());
        ProcessResult readRes = ProcessRunner.run(readCmd, projectRoot, Map.of());

        // It should fail or return empty/error because the parent of projectRoot (tempDir) is not mounted.
        assertNotEquals(0, readRes.exitCode(), "Reading host file outside sandbox should fail. Output: "
                + readRes.output() + ", Error: " + readRes.errorOutput());
        assertFalse(readRes.output().contains("secret"), "Output should not contain the secret");

        // 2. Verify Write Denied for system path
        List<String> writeCmd = buildContainerService.buildNativeSandboxCommand(projectRoot, config,
                "touch /usr/roxy_test_token");
        ProcessResult writeRes = ProcessRunner.run(writeCmd, projectRoot, Map.of());
        assertNotEquals(0, writeRes.exitCode(), "Writing to system path should be denied");

        // 3. Verify Build Success (simple command)
        List<String> successCmd = buildContainerService.buildNativeSandboxCommand(projectRoot, config,
                "echo 'hello from sandbox'");
        ProcessResult successRes = ProcessRunner.run(successCmd, projectRoot, Map.of());
        assertEquals(0, successRes.exitCode());
        assertTrue(successRes.output().contains("hello from sandbox"));
    }
}

package org.roxycode.app.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PromptServiceTest {

    private PromptService promptService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // We need to override the user home or internal logic to use tempDir for testing
        // Since getOverridePath is private, I'll use a hack or modify PromptService to be more testable
        // For now, let's just test that it can load the default one from classpath (mocking the resource is hard)
        // Actually, let's just test the logic by making it package-private or providing a way to inject base path
        promptService = new PromptService() {
            @Override
            protected java.nio.file.Path getOverridePath() {
                return tempDir.resolve("core_workflow.md");
            }
        };
    }

    @Test
    void testSaveAndLoadPrompt() {
        String content = "test prompt content";
        promptService.saveCoreWorkflowPrompt(content);
        assertEquals(content, promptService.loadCoreWorkflowPrompt());
    }

    @Test
    void testResetPrompt() {
        String content = "test prompt content";
        promptService.saveCoreWorkflowPrompt(content);
        promptService.resetCoreWorkflowPrompt();
        // Should return default from classpath because override is deleted
        // Note: this will fail if classpath resource is missing in test context
        assertNotEquals(content, promptService.loadCoreWorkflowPrompt());
    }
}

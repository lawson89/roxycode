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
        assertNotEquals(content, promptService.loadCoreWorkflowPrompt());
    }

    @Test
    void testLoadAllPrompts() {
        String allPrompts = promptService.loadAllPrompts();
        assertNotNull(allPrompts);
        // core_workflow.md should NOT be in the result of loadAllPrompts
        assertFalse(allPrompts.contains("## core_workflow.md"));
    }

    @Test
    void testLoadAllDocs() {
        String allDocs = promptService.loadAllDocs();
        assertNotNull(allDocs);
        // jexl.md should NOT be in the result of loadAllDocs
        assertFalse(allDocs.contains("## jexl.md"));
    }
}
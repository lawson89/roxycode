package org.roxycode.app.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PromptServiceTest {

    private PromptService promptService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        promptService = new PromptService();
    }

    @Test
    void testLoadCoreWorkflowPrompt() {
        String prompt = promptService.loadCoreWorkflowPrompt();
        assertNotNull(prompt);
        assertTrue(prompt.contains("ROXY CORE WORKFLOW PROTOCOL"));
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

    @Test
    void testLoadExplorePrompt() {
        String prompt = promptService.loadExplorePrompt();
        assertNotNull(prompt);
        assertTrue(prompt.contains("EXPLORE phase"));
    }

    @Test
    void testLoadProjectContext() throws IOException {
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);

        PromptService serviceWithProject = new PromptService(projectService);
        // Case 1: no .roxycode/context directory
        assertEquals("", serviceWithProject.loadProjectContext());

        // Case 2: .roxycode/context with markdown files
        Path contextDir = tempDir.resolve(".roxycode/context");
        Files.createDirectories(contextDir);
        Files.writeString(contextDir.resolve("b_architecture.md"), "Architecture guidelines");
        Files.writeString(contextDir.resolve("a_rules.md"), "Project rules");
        Files.writeString(contextDir.resolve("ignore.txt"), "Ignore me");

        String context = serviceWithProject.loadProjectContext();
        assertTrue(context.contains("## Project Context: a_rules.md"));
        assertTrue(context.contains("Project rules"));
        assertTrue(context.contains("## Project Context: b_architecture.md"));
        assertTrue(context.contains("Architecture guidelines"));
        assertFalse(context.contains("ignore.txt"));

        // Alphabetical ordering check: a_rules before b_architecture
        int idxA = context.indexOf("a_rules.md");
        int idxB = context.indexOf("b_architecture.md");
        assertTrue(idxA < idxB, "Context files should be sorted alphabetically");
    }
}
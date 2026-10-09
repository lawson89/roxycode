package org.roxycode.app.ai.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.service.ProjectService;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FileEditorServiceTest {

    @TempDir
    Path tempDir;

    private ProjectService projectService;
    private WorkflowService workflowService;
    private ApplicationEventPublisher eventPublisher;
    private FileEditorService fileEditorService;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        workflowService = mock(WorkflowService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        fileEditorService = new FileEditorService(projectService, workflowService, eventPublisher);

        when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);
        when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.DEVELOP);
    }

    @Test
    void testWriteFileSuccess() throws IOException {
        String relativePath = "test.txt";
        String content = "Hello World";

        fileEditorService.writeFile(relativePath, content);

        Path filePath = tempDir.resolve(relativePath);
        assertTrue(Files.exists(filePath));
        assertEquals(content, Files.readString(filePath));
        verify(eventPublisher, atLeastOnce()).publishEvent(isA(FileChangedEvent.class));
    }

    @Test
    void testWriteFileWrongPhaseThrowsException() {
        when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);

        assertThrows(IllegalStateException.class, () -> 
            fileEditorService.writeFile("test.txt", "content")
        );
    }

    @Test
    void testReplaceBlockSuccess() throws IOException {
        Path filePath = tempDir.resolve("test.java");
        Files.writeString(filePath, "public class Test {\n // TODO\n }");

        fileEditorService.replaceBlock("test.java", "// TODO", "// Done");

        String content = Files.readString(filePath);
        assertTrue(content.contains("// Done"));
        assertFalse(content.contains("// TODO"));
        verify(eventPublisher, atLeastOnce()).publishEvent(isA(FileChangedEvent.class));
    }

    @Test
    void testReplaceBlockWithVariableWhitespace() throws IOException {
        Path filePath = tempDir.resolve("test.java");
        Files.writeString(filePath, "public    void   myMethod() { }");

        // Should match despite different whitespace in target block
        fileEditorService.replaceBlock("test.java", "public void myMethod", "private int newMethod");

        String content = Files.readString(filePath);
        assertEquals("private int newMethod() { }", content);
    }

    @Test
    void testReplaceBlockNotUniqueThrowsException() throws IOException {
        Path filePath = tempDir.resolve("test.java");
        Files.writeString(filePath, "// TODO\n // TODO");

        assertThrows(IllegalArgumentException.class, () -> 
            fileEditorService.replaceBlock("test.java", "// TODO", "// Done")
        );
    }

    @Test
    void testReplaceLinesSuccess() throws IOException {
        Path filePath = tempDir.resolve("test.txt");
        Files.write(filePath, List.of("Line 1", "Line 2", "Line 3", "Line 4"));

        fileEditorService.replaceLines("test.txt", 2, 3, "New Content");

        List<String> lines = Files.readAllLines(filePath);
        assertEquals(3, lines.size());
        assertEquals("Line 1", lines.get(0));
        assertEquals("New Content", lines.get(1));
        assertEquals("Line 4", lines.get(2));
        verify(eventPublisher, atLeastOnce()).publishEvent(isA(FileChangedEvent.class));
    }

    @Test
    void testInsertAtLineSuccess() throws IOException {
        Path filePath = tempDir.resolve("test.txt");
        Files.write(filePath, List.of("Line 1", "Line 3"));

        fileEditorService.insertAtLine("test.txt", 2, "Line 2");

        List<String> lines = Files.readAllLines(filePath);
        assertEquals(3, lines.size());
        assertEquals("Line 2", lines.get(1));
        verify(eventPublisher, atLeastOnce()).publishEvent(isA(FileChangedEvent.class));
    }

    @Test
    void testPathTraversalThrowsSecurityException() {
        assertThrows(SecurityException.class, () -> 
            fileEditorService.writeFile("../outside.txt", "content")
        );
    }
}
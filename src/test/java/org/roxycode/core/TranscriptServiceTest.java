package org.roxycode.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class TranscriptServiceTest {

    private TranscriptService transcriptService;
    private RoxyProjectService roxyProjectService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        roxyProjectService = Mockito.mock(RoxyProjectService.class);

        when(roxyProjectService.getProjectRoot()).thenReturn(tempDir);
        when(roxyProjectService.getRoxyWorkingDir()).thenReturn(tempDir.resolve(".roxy"));
        when(roxyProjectService.getTranscriptsDir()).thenReturn(tempDir.resolve(".roxy").resolve("transcripts"));
        
        Files.createDirectories(tempDir.resolve(".roxy").resolve("transcripts"));

        transcriptService = new TranscriptService(roxyProjectService);
        transcriptService.setEnabled(true);
    }

    @Test
    void testLogging() throws IOException {
        transcriptService.log("user", "Hello");
        transcriptService.log("roxy", "Hi there");
        transcriptService.logToolCall("run_js", Collections.singletonMap("script", "console.log('test')"));
        transcriptService.logToolResult("run_js", "test output");

        Path transcriptsDir = tempDir.resolve(".roxy").resolve("transcripts");
        List<Path> files = Files.list(transcriptsDir).toList();
        assertEquals(1, files.size());

        String content = Files.readString(files.get(0));
        assertTrue(content.contains("USER"));
        assertTrue(content.contains("Hello"));
        assertTrue(content.contains("ROXY"));
        assertTrue(content.contains("Hi there"));
        assertTrue(content.contains("TOOL CALL: run_js"));
        assertTrue(content.contains("script=console.log('test')"));
        assertTrue(content.contains("TOOL RESULT: run_js"));
        assertTrue(content.contains("test output"));
    }
}

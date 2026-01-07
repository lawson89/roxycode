package org.roxycode.core;

import com.google.genai.types.*;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.roxycode.core.tools.ToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

@MicronautTest
@Property(name = "spec.name", value = "GenAIServiceTest")
class GenAIServiceTest {

    @Inject
    GenAIService genAIService;

    @Inject
    ToolRegistry toolRegistry;

    @Test
    void testServiceInitialization() {
        assertNotNull(genAIService);
    }

    @Test
    void testToolDiscoveryAndLoading(@TempDir Path tempRoot) throws Exception {
        // 1. Setup fake Roxy Home structure
        Path toolsDir = tempRoot.resolve("tools");
        Path contextDir = tempRoot.resolve("context");
        Files.createDirectories(toolsDir);
        Files.createDirectories(contextDir);

        // 2. Create dummy tool files
        Files.createFile(toolsDir.resolve("tool_a.toml"));
        Files.createFile(toolsDir.resolve("tool_b.toml"));
        toolRegistry.loadTools(toolsDir);
        // Verify it discovered the tools (refreshKnowledge calls getTool to build definitions)
        assertTrue(toolRegistry.getAllToolNames().contains("tool_a"), "Service failed to discover tool_a");
        assertTrue(toolRegistry.getAllToolNames().contains("tool_b"), "Service failed to discover tool_b");
    }

    @Test
    void testStopChatInterruption() {
        // We use a spy to override the actual Gemini API call
        GenAIService spyService = Mockito.spy(genAIService);
        // Mock generateContent to return a tool call (to keep the loop going if it wasn't stopped)
        FunctionCall mockCall = FunctionCall.builder().name("tool_a").args(Collections.emptyMap()).build();
        Content modelMessage = Content.builder().role("model").parts(List.of(Part.builder().functionCall(mockCall).build())).build();
        Candidate candidate = Candidate.builder().content(modelMessage).build();
        GenerateContentResponse response = GenerateContentResponse.builder().candidates(List.of(candidate)).build();
        Mockito.doReturn(response).when(spyService).doGenerateContent(anyString(), anyList(), any());
        // We trigger stopChat in the first turn's status update
        String result = spyService.chat("test prompt", "/tmp", List.of(), (status) -> {
            if (status.contains("Thinking")) {
                spyService.stopChat();
            }
        });
        assertEquals("Chat stopped by user.", result);
    }

}

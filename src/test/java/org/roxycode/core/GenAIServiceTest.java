package org.roxycode.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.roxycode.core.tools.ToolDefinition;
import org.roxycode.core.tools.ToolExecutionService;
import org.roxycode.core.tools.ToolRegistry;
import com.google.genai.types.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@MicronautTest
@Property(name = "spec.name", value = "GenAIServiceTest")
class GenAIServiceTest {

    @Inject
    GenAIService genAIService;

    @Inject
    ToolRegistry // This is StubToolRegistry
    toolRegistry;

    @Inject
    SettingsService // This is StubSettingsService
    settingsService;

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
        // Configure the stub settings to point to our temp root as ROXY_HOME
        ((StubSettingsService) settingsService).setRoxyHome(tempRoot);
        // 2. Create dummy tool files
        Files.createFile(toolsDir.resolve("tool_a.toml"));
        Files.createFile(toolsDir.resolve("tool_b.toml"));
        // 3. Trigger the Knowledge Refresh
        // FIX: We call refreshKnowledge() explicitly because chat() no longer scans the disk
        genAIService.refreshKnowledge(tempRoot.toString());
        // 4. Verify the Stub captured the correct interactions
        StubToolRegistry stubRegistry = (StubToolRegistry) toolRegistry;
        // Verify loadTools was called with the correct path (tempRoot/tools)
        assertEquals(toolsDir.toString(), stubRegistry.lastLoadedPath, "Service did not load tools from the expected directory");
        // Verify it discovered the tools (refreshKnowledge calls getTool to build definitions)
        assertTrue(stubRegistry.requestedTools.contains("tool_a"), "Service failed to discover tool_a");
        assertTrue(stubRegistry.requestedTools.contains("tool_b"), "Service failed to discover tool_b");
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

    // --- Manual Stubs to bypass Mockito/Java 25 issues ---
    @Singleton
    @Replaces(SettingsService.class)
    @Requires(property = "spec.name", value = "GenAIServiceTest")
    static class StubSettingsService extends SettingsService {

        private Path mockRoxyHome;

        public StubSettingsService() {
            // Pass a dummy mapper to satisfy constructor requirements
            super(new ObjectMapper());
        }

        public void setRoxyHome(Path path) {
            this.mockRoxyHome = path;
        }

        @Override
        public Path getRoxyHome() {
            // Return the mock path if set, otherwise default
            return mockRoxyHome != null ? mockRoxyHome : Paths.get("roxy_home");
        }

        @Override
        public String getGeminiApiKey() {
            return "dummy-key";
        }
    }

    @Singleton
    @Replaces(ToolRegistry.class)
    @Requires(property = "spec.name", value = "GenAIServiceTest")
    static class StubToolRegistry extends ToolRegistry {

        public String lastLoadedPath;

        public List<String> requestedTools = new ArrayList<>();

        @Override
        public void loadTools(String directoryPath) {
            this.lastLoadedPath = directoryPath;
        }

        @Override
        public Optional<ToolDefinition> getTool(String name) {
            this.requestedTools.add(name);
            ToolDefinition tool = new ToolDefinition();
            tool.setDescription("Stub tool: " + name);
            return Optional.of(tool);
        }
    }

    @Singleton
    @Replaces(ToolExecutionService.class)
    @Requires(property = "spec.name", value = "GenAIServiceTest")
    static class StubToolExecutionService extends ToolExecutionService {

        public StubToolExecutionService() {
            super(null, null, null, null, null, null, null, null, null);
        }

        @Override
        public Future<String> execute(ToolDefinition tool, Map<String, Object> args) {
            return CompletableFuture.completedFuture("Success");
        }
    }
}

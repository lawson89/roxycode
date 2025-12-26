package org.roxycode.core;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.core.tools.ToolDefinition;
import org.roxycode.core.tools.ToolExecutionService;
import org.roxycode.core.tools.ToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
@Property(name = "spec.name", value = "GenAIServiceTest")
class GenAIServiceTest {

    @Inject
    GenAIService genAIService;

    @Inject
    ToolRegistry toolRegistry; // This will be our StubToolRegistry

    @Test
    void testServiceInitialization() {
        assertNotNull(genAIService);
    }

    @Test
    void testToolDiscoveryAndLoading(@TempDir Path tempRoot) throws Exception {
        // 1. Setup a fake project structure
        Path resourcesDir = tempRoot.resolve("src/main/resources/tools");
        Files.createDirectories(resourcesDir);

        // 2. Create dummy tool files
        Files.createFile(resourcesDir.resolve("tool_a.toml"));
        Files.createFile(resourcesDir.resolve("tool_b.toml"));
        Files.createFile(resourcesDir.resolve("not_a_tool.txt")); // Should be ignored

        // 3. Trigger the chat method
        // We expect an error eventually (e.g., API key missing or connection fail),
        // but the tool loading happens FIRST.
        try {
            genAIService.chat("Test Prompt", tempRoot.toString());
        } catch (Exception e) {
            // Ignore API connection errors
        }

        // 4. Verify the Stub captured the correct interactions
        StubToolRegistry stubRegistry = (StubToolRegistry) toolRegistry;

        // Verify loadTools was called with the correct path
        assertEquals(resourcesDir.toString(), stubRegistry.lastLoadedPath,
                "Service did not load tools from the expected directory");

        // Verify it tried to fetch the specific tools we created
        assertTrue(stubRegistry.requestedTools.contains("tool_a"), "Service failed to discover tool_a");
        assertTrue(stubRegistry.requestedTools.contains("tool_b"), "Service failed to discover tool_b");

        // Verify it did NOT try to fetch the text file
        assertFalse(stubRegistry.requestedTools.contains("not_a_tool"), "Service incorrectly loaded a .txt file");
    }

    // --- Manual Stubs to bypass Mockito/Java 25 issues ---

    @Singleton
    @Replaces(ToolRegistry.class)
    @Requires(property = "spec.name", value = "GenAIServiceTest")
    static class StubToolRegistry extends ToolRegistry {

        // Fields to record interactions for assertions
        public String lastLoadedPath;
        public List<String> requestedTools = new ArrayList<>();

        @Override
        public void loadTools(String directoryPath) {
            this.lastLoadedPath = directoryPath;
        }

        @Override
        public Optional<ToolDefinition> getTool(String name) {
            this.requestedTools.add(name);

            // Return a dummy tool so the service continues execution
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
            super(null);
        }

        @Override
        public Future<String> execute(ToolDefinition tool, Map<String, Object> args) {
            return CompletableFuture.completedFuture("Success");
        }
    }
}
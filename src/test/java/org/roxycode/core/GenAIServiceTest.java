package org.roxycode.core;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;
import org.roxycode.core.tools.ToolDefinition;
import org.roxycode.core.tools.ToolExecutionService;
import org.roxycode.core.tools.ToolRegistry;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@MicronautTest
@Property(name = "spec.name", value = "GenAIServiceTest")
class GenAIServiceTest {

    @Inject
    GenAIService genAIService;

    @Test
    void testServiceInitialization() {
        // Just verifying the bean is created and wired correctly
        assertNotNull(genAIService);
    }

    // --- Manual Stubs to bypass Mockito/Java 25 issues ---

    @Singleton
    @Replaces(ToolRegistry.class)
    @Requires(property = "spec.name", value = "GenAIServiceTest")
    static class StubToolRegistry extends ToolRegistry {
        @Override
        public Optional<ToolDefinition> getTool(String name) {
            // Return a dummy tool so the service doesn't crash on init
            ToolDefinition tool = new ToolDefinition();
            tool.setDescription("Stub tool");
            return Optional.of(tool);
        }
    }

    @Singleton
    @Replaces(ToolExecutionService.class)
    @Requires(property = "spec.name", value = "GenAIServiceTest")
    static class StubToolExecutionService extends ToolExecutionService {
        public StubToolExecutionService() {
            super(null); // Pass null as we won't use the sandbox here
        }

        @Override
        public Future<String> execute(ToolDefinition tool, Map<String, Object> args) {
            return CompletableFuture.completedFuture("Success");
        }
    }
}
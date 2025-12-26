package org.roxycode.core.tools;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class ToolExecutionTest {

    @Inject
    ToolExecutionService executionService;

    @Test
    void testInlineExecution() throws ExecutionException, InterruptedException {
        ToolDefinition tool = new ToolDefinition();
        tool.setSource("return 'Hello ' + args.name");

        Map<String, Object> args = new HashMap<>();
        args.put("name", "Roxy");

        Future<String> result = executionService.execute(tool, args);
        assertEquals("Hello Roxy", result.get());
    }

    @Test
    void testFileResolution(@TempDir Path tempDir) throws Exception {
        // 1. Create a script file
        Path scriptPath = tempDir.resolve("myscript.groovy");

        // FIX: Updated method name from getProjectRoot() to getRoot()
        Files.writeString(scriptPath, "return sandbox.getRoot().toString()");

        // 2. Mock a ToolDefinition located in the same folder
        ToolDefinition tool = new ToolDefinition();
        tool.setSource("file:myscript.groovy");
        tool.setDefinitionLocation(tempDir);

        // 3. Execute
        // Note: The Sandbox in the service is likely bound to '.' or a default.
        // We just want to ensure the script runs and can access the sandbox variable.
        Future<String> result = executionService.execute(tool, new HashMap<>());

        assertNotNull(result.get());
        assertTrue(result.get().length() > 0);
    }
}
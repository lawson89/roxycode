package org.roxycode.core.context;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class ContextRegistryTest {

    @Inject
    ContextRegistry contextRegistry;

    @Test
    void testLoadContexts(@TempDir Path tempRoot) throws IOException {
        // 1. Setup a specific folder for contexts
        // Since the registry now accepts any Path, we don't need to match the old src/main/resources structure
        Path contextDir = tempRoot.resolve("roxy_contexts");
        Files.createDirectories(contextDir);

        // 2. Create a complex TOML file
        String tomlContent = """
                description = "Test Description"
                
                context = \"\"\"
                This is some inline documentation.
                It has multiple lines.
                \"\"\"
                
                files = ["file1.xml", "file2.dtd"]
                """;
        Files.writeString(contextDir.resolve("complex.toml"), tomlContent);

        // 3. Create a simple TOML file (just description)
        Files.writeString(contextDir.resolve("simple.toml"), "description = \"Simple Description\"");

        // 4. Load contexts
        // FIX: Pass the Path object directly, not a String
        contextRegistry.loadContexts(contextDir);

        // 5. Verify Menu Generation
        String menu = contextRegistry.getContextMenu();
        System.out.println("Generated Menu:\n" + menu);

        assertNotNull(menu);
        assertTrue(menu.contains("complex.toml: Test Description"));
        assertTrue(menu.contains("simple.toml: Simple Description"));

        // Check for details in complex entry
        assertTrue(menu.contains("Contains inline documentation"));
        assertTrue(menu.contains("Refers to: file1.xml, file2.dtd"));
    }

    @Test
    void testMissingDirectoryHandlesGracefully(@TempDir Path emptyRoot) {
        Path missingDir = emptyRoot.resolve("does_not_exist");

        // FIX: Pass Path object
        assertDoesNotThrow(() -> contextRegistry.loadContexts(missingDir));
        assertEquals("", contextRegistry.getContextMenu());
    }
}
package org.roxycode.app.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

public class JexlDocumentationTest {
    @Test
    public void testJexlDocExists() {
        Path path = Paths.get("src/main/resources/docs/jexl.md");
        assertTrue(
                Files.exists(path),
                "JEXL documentation should exist at src/main/resources/docs/jexl.md");
    }

    @Test
    public void testJexlDocForbidsArbitraryClassInstantiationAndUsesValidServices()
            throws IOException {
        Path path = Paths.get("src/main/resources/docs/jexl.md");
        String content = Files.readString(path);
        assertTrue(content.contains("FORBIDDEN: Arbitrary Java Class Instantiation"));
        assertTrue(content.contains("buildToolService"));
        assertTrue(content.contains("fileEditorService"));
        assertTrue(content.contains("fileReadService"));
        assertTrue(content.contains("grepService"));
        assertTrue(content.contains("JEXL API Documentation"));
        assertFalse(content.contains("textEditorService"));
        assertFalse(content.contains("genericBuildToolService"));
        assertFalse(content.contains("ColinxCode"));
    }
}

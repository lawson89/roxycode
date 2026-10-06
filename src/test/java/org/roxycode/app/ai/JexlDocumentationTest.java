package org.roxycode.app.ai;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JexlDocumentationTest {
    @Test
    public void testJexlDocExists() {
        Path path = Paths.get("src/main/resources/docs/jexl.md");
        assertTrue(Files.exists(path), "JEXL documentation should exist at src/main/resources/docs/jexl.md");
    }
}

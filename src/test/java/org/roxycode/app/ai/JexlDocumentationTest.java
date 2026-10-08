package org.roxycode.app.ai;

import org.junit.jupiter.api.Test;
import java.io.IOException;
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

    @Test
    public void testJexlDocForbidsArbitraryClassInstantiation() throws IOException {
        Path path = Paths.get("src/main/resources/docs/jexl.md");
        String content = Files.readString(path);
        assertTrue(content.contains("FORBIDDEN: Arbitrary Java Class Instantiation"));
        assertTrue(content.contains("buildToolService.buildAndTest()"));
    }
}

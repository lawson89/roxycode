package org.roxycode.app.ai.services.cache;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileTreeFormatterTest {

    @Test
    void testFormat() {
        List<String> paths = List.of(
            "src/main/java/App.java",
            "src/main/resources/config.xml",
            "pom.xml",
            "README.md"
        );

        String tree = FileTreeFormatter.format(paths);
        System.out.println(tree);

        assertTrue(tree.contains("├── README.md"));
        assertTrue(tree.contains("├── pom.xml"));
        assertTrue(tree.contains("└── src/"));
        assertTrue(tree.contains("    └── main/"));
        assertTrue(tree.contains("        ├── java/"));
        assertTrue(tree.contains("        │   └── App.java"));
    }

    @Test
    void testEmptyPaths() {
        String tree = FileTreeFormatter.format(List.of());
        assertTrue(tree.equals("."));
    }
}
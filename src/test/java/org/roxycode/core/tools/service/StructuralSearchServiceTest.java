package org.roxycode.core.tools.service;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.core.Sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class StructuralSearchServiceTest {

    @Inject
    StructuralSearchService searchService;

    @Inject
    Sandbox sandbox;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        sandbox.setRoot(tempDir.toString());
    }

    @Test
    void testFindEmptyCatchBlocks() throws IOException {
        String code = """
                public class Test {
                    void foo() {
                        try {
                            int x = 1;
                        } catch (Exception e) {
                        }
                    }
                }
                """;
        Files.writeString(tempDir.resolve("Test.java"), code);

        List<SearchResult> results = searchService.findEmptyCatchBlocks(".");
        assertEquals(1, results.size());
        assertEquals("Test", results.get(0).className());
        assertTrue(results.get(0).snippet().contains("catch (Exception e)"));
    }

    @Test
    void testFindDeprecatedWithoutJavadoc() throws IOException {
        String code = """
                public class Test {
                    @Deprecated
                    void foo() {}
                    
                    /**
                     * Valid deprecation
                     */
                    @Deprecated
                    void bar() {}
                }
                """;
        Files.writeString(tempDir.resolve("Test.java"), code);

        List<SearchResult> results = searchService.findDeprecatedWithoutJavadoc(".");
        assertEquals(1, results.size());
        assertEquals("foo", results.get(0).elementName());
    }

    @Test
    void testFindMethodsWithTooManyParameters() throws IOException {
        String code = """
                public class Test {
                    void foo(int a, int b, int c) {}
                    void bar(int a) {}
                }
                """;
        Files.writeString(tempDir.resolve("Test.java"), code);

        List<SearchResult> results = searchService.findMethodsWithTooManyParameters(".", 2);
        assertEquals(1, results.size());
        assertEquals("foo", results.get(0).elementName());
    }

    @Test
    void testFindLargeClasses() throws IOException {
        String code = """
                public class Large {
                    // line 2
                    // line 3
                    // line 4
                    // line 5
                }
                """;
        Files.writeString(tempDir.resolve("Large.java"), code);

        List<SearchResult> results = searchService.findLargeClasses(".", 3);
        assertEquals(1, results.size());
        assertEquals("Large", results.get(0).className());
    }
}

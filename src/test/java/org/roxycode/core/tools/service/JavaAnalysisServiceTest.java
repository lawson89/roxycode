package org.roxycode.core.tools.service;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class JavaAnalysisServiceTest {

    @Inject
    JavaService javaAnalysisService;

    @TempDir
    Path tempDir;

    @Test
    void testAnalyzeFile() throws IOException {
        Path path = tempDir.resolve("TestClass.java");
        Files.writeString(path, """
            package test;
            public class TestClass {
                public void method1() {}
                public void method2() {}
            }
            """);

        JavaService.JavaFileSummary summary = javaAnalysisService.analyzeFile(path);

        assertNotNull(summary);
        assertEquals(1, summary.classes().size());
        assertEquals("TestClass", summary.classes().get(0).name());
        assertFalse(summary.classes().get(0).isInterface());
        assertEquals(2, summary.classes().get(0).methods().size());
    }

    @Test
    void testAnalyzeFileWithFields() throws IOException {
        Path path = tempDir.resolve("TestClassWithFields.java");
        Files.writeString(path, """
            package test;
            public class TestClassWithFields {
                private String field1;
                public int field2 = 10;
                
                public void method1() {}
            }
            """);

        JavaService.JavaFileSummary summary = javaAnalysisService.analyzeFile(path);

        assertNotNull(summary);
        assertEquals(1, summary.classes().size());
        JavaService.ClassSummary classSummary = summary.classes().get(0);
        assertEquals("TestClassWithFields", classSummary.name());
        assertEquals(2, classSummary.fields().size());
        
        JavaService.FieldSummary field1 = classSummary.fields().stream()
                .filter(f -> f.name().equals("field1"))
                .findFirst()
                .orElseThrow();
        assertEquals("String", field1.type());
        
        JavaService.FieldSummary field2 = classSummary.fields().stream()
                .filter(f -> f.name().equals("field2"))
                .findFirst()
                .orElseThrow();
        assertEquals("int", field2.type());
    }

    @Test
    void testGetMethodSource() throws IOException {
        Path path = tempDir.resolve("TestClass.java");
        Files.writeString(path, """
            package test;
            public class TestClass {
                public void method1() {
                    System.out.println("Hello");
                }
            }
            """);

        Optional<String> source = javaAnalysisService.getMethodSource(path, "TestClass", "method1");

        assertTrue(source.isPresent());
        assertTrue(source.get().contains("System.out.println(\"Hello\");"));
    }

    @Test
    void testReplaceMethod() throws IOException {
        Path path = tempDir.resolve("TestClass.java");
        Files.writeString(path, """
            package test;
            public class TestClass {
                public void method1() {
                    System.out.println("Old");
                }
            }
            """);

        String newMethod = """
            public void method1() {
                System.out.println("New");
            }
            """;

        javaAnalysisService.replaceMethod(path, "TestClass", "method1", newMethod);

        String updatedContent = Files.readString(path);
        assertTrue(updatedContent.contains("System.out.println(\"New\");"));
        assertFalse(updatedContent.contains("System.out.println(\"Old\");"));
    }

    @Test
    void testGetFieldSource() throws IOException {
        Path path = tempDir.resolve("TestClassWithFields.java");
        Files.writeString(path, """
            package test;
            public class TestClassWithFields {
                private String field1 = "Original";
            }
            """);

        Optional<String> source = javaAnalysisService.getFieldSource(path, "TestClassWithFields", "field1");

        assertTrue(source.isPresent());
        assertTrue(source.get().contains("private String field1 = \"Original\";"));
    }

    @Test
    void testReplaceField() throws IOException {
        Path path = tempDir.resolve("TestClassWithFields.java");
        Files.writeString(path, """
            package test;
            public class TestClassWithFields {
                private String field1 = "OldValue";
            }
            """);

        String newField = "private String field1 = \"NewValue\";";

        javaAnalysisService.replaceField(path, "TestClassWithFields", "field1", newField);

        String updatedContent = Files.readString(path);
        assertTrue(updatedContent.contains("private String field1 = \"NewValue\";"));
        assertFalse(updatedContent.contains("OldValue"));
    }
}
